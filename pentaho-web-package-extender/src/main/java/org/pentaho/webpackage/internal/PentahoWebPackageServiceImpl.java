/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pentaho.webpackage.internal;

import org.pentaho.webpackage.PentahoWebPackageBundle;
import org.pentaho.webpackage.PentahoWebPackageService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of the WebContainer service.
 */
public class PentahoWebPackageServiceImpl implements PentahoWebPackageService, BundleListener {
  private final Map<Long, PentahoWebPackageBundle> pentahoWebPackageBundles = new HashMap<>();

  private BundleContext bundleContext;

  public void setBundleContext( BundleContext bundleContext ) {
    this.bundleContext = bundleContext;
  }

  @Override
  public void bundleChanged( BundleEvent bundleEvent ) {
    final Bundle bundle = bundleEvent.getBundle();

    final int bundleEventType = bundleEvent.getType();
    if ( bundleEventType == BundleEvent.STARTING || bundleEventType == BundleEvent.STARTED ) {
      // For starting bundles, ensure, it's a lazy activation,
      // else we'll wait for the bundle to become ACTIVE
      if ( bundleEventType == BundleEvent.STARTING ) {
        String activationPolicyHeader = (String) bundle.getHeaders().get( Constants.BUNDLE_ACTIVATIONPOLICY );
        if ( activationPolicyHeader == null || !activationPolicyHeader.startsWith( Constants.ACTIVATION_LAZY ) ) {
          // Do not track this bundle yet
          return;
        }
      }

      PentahoWebPackageBundle extendedBundle = extendBundle( bundle );

      if ( extendedBundle != null ) {
        synchronized ( pentahoWebPackageBundles ) {
          if ( pentahoWebPackageBundles.putIfAbsent( bundle.getBundleId(), extendedBundle ) != null ) {
            return;
          }
        }

        extendedBundle.init();
      }
    } else if ( bundleEventType == BundleEvent.UNINSTALLED
        || bundleEventType == BundleEvent.UNRESOLVED
        || bundleEventType == BundleEvent.STOPPED ) {
      PentahoWebPackageBundle pwpc = pentahoWebPackageBundles.remove( bundle.getBundleId() );
      if ( pwpc != null ) {
        pwpc.destroy();
      }
    }
  }

  private PentahoWebPackageBundleImpl extendBundle( final Bundle bundle ) {
    if ( bundle == null ) {
      return null;
    }

    if ( bundle.getState() != Bundle.ACTIVE ) {
      return null;
    }

    // Check that this is a web bundle
    String provideCapabilityHeader = getHeader( bundle, "Provide-Capability" );
    if ( provideCapabilityHeader == null || !provideCapabilityHeader.contains( CAPABILITY_NAMESPACE ) ) {
      return null;
    }

    return new PentahoWebPackageBundleImpl( bundle );
  }

  private static String getHeader( final Bundle bundle, String... keys ) {
    BundleContext bundleContext = bundle.getBundleContext();

    // Look in the bundle...
    Dictionary<String, String> headers = bundle.getHeaders();
    for ( String key : keys ) {
      String value = headers.get( key );
      if ( value != null ) {
        return value;
      }
    }

    // Next, look in the bundle's fragments.
    Bundle[] bundles = bundleContext.getBundles();
    for ( Bundle fragment : bundles ) {
      // only fragments are in resolved state
      if ( fragment.getState() != Bundle.RESOLVED ) {
        continue;
      }

      // A fragment must also have the FRAGMENT_HOST header and the
      // FRAGMENT_HOST header
      // must be equal to the bundle symbolic name
      String fragmentHost = fragment.getHeaders().get( Constants.FRAGMENT_HOST );
      if ( ( fragmentHost == null ) || ( !fragmentHost.equals( bundle.getSymbolicName() ) ) ) {
        continue;
      }

      headers = fragment.getHeaders();
      for ( String key : keys ) {
        String value = headers.get( key );
        if ( value != null ) {
          return value;
        }
      }
    }

    return null;
  }
}
