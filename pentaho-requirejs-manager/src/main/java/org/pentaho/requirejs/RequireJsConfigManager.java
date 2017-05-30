/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2017 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.requirejs;

import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RequireJsConfigManager {
  static final String PACKAGE_JSON_PATH = "META-INF/js/package.json";
  static final String REQUIRE_JSON_PATH = "META-INF/js/require.json";
  static final String EXTERNAL_RESOURCES_JSON_PATH = "META-INF/js/externalResources.json";
  static final String STATIC_RESOURCES_JSON_PATH = "META-INF/js/staticResources.json";
  private static final ScheduledExecutorService executorService =
      Executors.newScheduledThreadPool( 2, r -> {
        Thread thread = Executors.defaultThreadFactory().newThread( r );
        thread.setDaemon( true );
        thread.setName( "RequireJSConfigManager pool" );
        return thread;
      } );
  private final ConcurrentHashMap<Long, List<Map<String, Object>>> configMap = new ConcurrentHashMap<>();
  private final Map<Long, RequireJsConfiguration> requireConfigMap = new HashMap<>();
  private final JSONParser parser = new JSONParser();
  String CAPABILITY_NAMESPACE = "org.pentaho.webpackage";
  private BundleContext bundleContext;

  private volatile ConcurrentHashMap<String, Future<String>> cachedConfigurations;
  private volatile long lastModified = System.currentTimeMillis();

  private RequireJsBundleListener bundleListener;

  public Map<String, Object> parseJsonPackage( URL resourceUrl ) {
    try {
      URLConnection urlConnection = resourceUrl.openConnection();

      InputStream inputStream = urlConnection.getInputStream();
      InputStreamReader inputStreamReader = new InputStreamReader( inputStream );
      BufferedReader bufferedReader = new BufferedReader( inputStreamReader );

      return (Map<String, Object>) parser.parse( bufferedReader );
    } catch ( Exception e ) {
      throw new RuntimeException( "Error opening " + resourceUrl.getPath(), e );
    }
  }

  public void setBundleContext( BundleContext bundleContext ) {
    this.bundleContext = bundleContext;
  }

  public void init() throws Exception {
    if ( this.bundleListener != null ) {
      throw new Exception( "Already initialized." );
    }

    // setting initial capacity to three (relative url and absolute http/https url scenarios)
    this.cachedConfigurations = new ConcurrentHashMap<>( 3 );

    this.bundleListener = new RequireJsBundleListener( this );
    this.bundleContext.addBundleListener( this.bundleListener );

    for ( Bundle bundle : this.bundleContext.getBundles() ) {
      this.updateBundleContext( bundle );
    }
    this.updateBundleContext( this.bundleContext.getBundle() );
  }

  public void destroy() {
    this.invalidateCachedConfigurations();

    if ( this.bundleListener != null ) {
      this.bundleContext.removeBundleListener( this.bundleListener );
    }

    this.bundleListener = null;
  }

  public void bundleChanged( Bundle bundle ) {
    boolean shouldRefresh = true;
    try {
      shouldRefresh = this.updateBundleContext( bundle );
    } catch ( Exception e ) {
      // Ignore TODO possibly log
    } finally {
      if ( shouldRefresh ) {
        this.invalidateCachedConfigurations();
      }
    }
  }

  public String getRequireJsConfig( String baseUrl ) {
    String result = null;
    int tries = 3;
    Exception lastException = null;
    while ( tries-- > 0 && result == null ) {
      Future<String> cache = this.getCachedConfiguration( baseUrl );

      try {
        result = cache.get();
      } catch ( InterruptedException e ) {
        // ignore
      } catch ( ExecutionException e ) {
        lastException = e;

        this.invalidateCachedConfigurations();
      }
    }

    if ( result == null ) {
      result = "{}; // Error computing RequireJS Config: ";
      if ( lastException != null && lastException.getCause() != null ) {
        result += lastException.getCause().getMessage();
      } else {
        result += "unknown error";
      }
    }

    return result;
  }

  public long getLastModified() {
    return this.lastModified;
  }

  private boolean updateBundleContext( Bundle bundle ) {
    switch ( bundle.getState() ) {
      case Bundle.STOPPING:
      case Bundle.RESOLVED:
      case Bundle.UNINSTALLED:
      case Bundle.INSTALLED:
        return this.updateBundleContextStopped( bundle );
      case Bundle.ACTIVE:
        return this.updateBundleContextActivated( bundle );
      default:
        return true;
    }
  }

  private boolean updateBundleContextStopped( Bundle bundle ) {
    List<Map<String, Object>> bundleConfig = this.configMap.remove( bundle.getBundleId() );
    RequireJsConfiguration requireJsConfiguration = this.requireConfigMap.remove( bundle.getBundleId() );

    return bundleConfig != null || requireJsConfiguration != null;
  }

  private boolean updateBundleContextActivated( Bundle bundle ) {
    boolean shouldInvalidate = false;

    BundleWiring wiring = bundle.adapt( BundleWiring.class );

    List<BundleCapability> capabilities = wiring.getCapabilities( CAPABILITY_NAMESPACE );
    capabilities.forEach( bundleCapability -> {
      Map<String, Object> attributes = bundleCapability.getAttributes();

      String name = (String) attributes.get( "name" );
      Version version = (Version) attributes.get( "version" );
      String root = (String) attributes.get( "root" );
      if ( root == null || "".equals( root ) ) {
        root = "";
      }

      if ( name != null && version != null ) {
        Map<String, Object> packageInfo = new HashMap<>();
        packageInfo.put( "name", name );
        packageInfo.put( "version", version );

        Map<String, Object> dependenciesInfo = new HashMap<>();

        // TODO: This doesn't get the requirements satisfied by the bundle itself,
        // so dependencies between bundled modules must be taken care by other means
        List<BundleWire> requirements = wiring.getRequiredWires( CAPABILITY_NAMESPACE );
        requirements.forEach( bundleWire -> {
          Map<String, Object> attributes1 = bundleWire.getCapability().getAttributes();

          String name1 = (String) attributes1.get( "name" );
          Version version1 = (Version) attributes1.get( "version" );

          dependenciesInfo.put( name1, version1 );
        } );

        packageInfo.put( "dependencies", dependenciesInfo );

        try {
          URL packageJsonUrl = bundle.getResource( root + "/package.json" );
          if ( packageJsonUrl != null ) {
            Map<String, Object> packageJson = this.parseJsonPackage( packageJsonUrl );
            packageJson.forEach( packageInfo::putIfAbsent );
          }
        } catch ( RuntimeException ignored ) {
          // throwing will make everything fail
          // what damage control should we do? ignore and use only the capability info?
          // don't register this capability?
          // don't register all the bundle's capabilities?
          // this is all post-bundle wiring phase, so only the requirejs configuration is affected
          // the bundle is started and nothing will change that... or should we bundle.stop()?
        }

        RequireJsGenerator gen = new RequireJsGenerator( packageInfo );

        RequireJsGenerator.ArtifactInfo artifactInfo =
            new RequireJsGenerator.ArtifactInfo( "osgi-bundles", bundle.getSymbolicName(),
                bundle.getVersion().toString() );
        final RequireJsGenerator.ModuleInfo moduleInfo;

        try {
          moduleInfo = gen.getConvertedConfig( artifactInfo );

          Map<String, Object> requireJsonObject = moduleInfo.getRequireJs();

          this.putInConfigMap( bundle.getBundleId(), requireJsonObject );
        } catch ( ParseException e ) {
          e.printStackTrace();
        }
      }
    } );

    shouldInvalidate = true;

    return shouldInvalidate;
  }

  private void putInConfigMap( long bundleId, Map<String, Object> config ) {
    List<Map<String, Object>> bundleConfigurations = this.configMap.computeIfAbsent( bundleId, key -> new ArrayList<>() );
    bundleConfigurations.add( config );
  }

  // package-private for unit testing
  Future<String> getCachedConfiguration( String baseUrl ) {
    return this.cachedConfigurations.computeIfAbsent( baseUrl, k -> {
      this.lastModified = System.currentTimeMillis();

      List<Map<String, Object>> configurations = new ArrayList<>();

      this.configMap.values().forEach( configurations::addAll );

      return executorService.schedule( new RebuildCacheCallable( baseUrl, configurations,
          new ArrayList<>( this.requireConfigMap.values() ) ), 250, TimeUnit.MILLISECONDS );
    } );
  }

  // package-private for unit testing
  void invalidateCachedConfigurations() {
    this.lastModified = System.currentTimeMillis();

    this.cachedConfigurations.forEach( ( s, stringFuture ) -> stringFuture.cancel( true ) );
    this.cachedConfigurations.clear();
  }

}
