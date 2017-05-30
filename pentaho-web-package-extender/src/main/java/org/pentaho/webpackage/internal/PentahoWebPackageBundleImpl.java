/*!
 * Copyright 2010 - 2017 Pentaho Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pentaho.webpackage.internal;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleWiring;
import org.pentaho.webpackage.PentahoWebPackage;
import org.pentaho.webpackage.PentahoWebPackageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PentahoWebPackageBundleImpl extends PentahoWebPackageBundleAbstract {
  private final Bundle bundle;
  private final BundleContext bundleContext;

  PentahoWebPackageBundleImpl( Bundle bundle ) {
    this.bundle = bundle;
    this.bundleContext = bundle.getBundleContext();

    // get the bundle name
    String name = this.bundle.getHeaders().get( Constants.BUNDLE_NAME );
    // if there is no name, then default to symbolic name
    name = ( name == null ) ? this.bundle.getSymbolicName() : name;
    // if there is no symbolic name, resort to location
    name = ( name == null ) ? this.bundle.getLocation() : name;
    // get the bundle version
    String version = this.bundle.getHeaders().get( Constants.BUNDLE_VERSION );
    name = ( ( version != null ) ) ? name + " (" + version + ")" : name;

    long bundleId = this.bundle.getBundleId();

    this.setBundleId( bundleId );
    this.setName( name );
  }

  @Override
  public void init() {
    BundleWiring wiring = this.bundle.adapt( BundleWiring.class );

    if ( wiring != null ) {
      ArrayList<PentahoWebPackage> pentahoWebPackages = new ArrayList<>();

      List<BundleCapability> capabilities = wiring.getCapabilities( PentahoWebPackageService.CAPABILITY_NAMESPACE );
      capabilities.forEach( bundleCapability -> {
        Map<String, Object> attributes = bundleCapability.getAttributes();

        String name = (String) attributes.get( "name" );
        Version version = (Version) attributes.get( "version" );
        String root = (String) attributes.get( "root" );

        if ( name != null && version != null ) {
          pentahoWebPackages.add( new PentahoWebPackageImpl( bundle, name, version, root ) );
        }
      } );

      pentahoWebPackages.forEach( PentahoWebPackage::init );
    }
  }
}
