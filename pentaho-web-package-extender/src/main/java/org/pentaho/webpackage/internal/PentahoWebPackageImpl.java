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

import org.ops4j.pax.web.extender.whiteboard.ExtenderConstants;
import org.ops4j.pax.web.extender.whiteboard.ResourceMapping;
import org.ops4j.pax.web.extender.whiteboard.runtime.DefaultResourceMapping;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.service.http.HttpContext;
import org.pentaho.webpackage.PentahoWebPackage;
import org.pentaho.webpackage.PentahoWebPackageService;

import java.io.IOException;
import java.net.URL;
import java.util.Dictionary;
import java.util.Hashtable;

public class PentahoWebPackageImpl implements PentahoWebPackage {
  private final Bundle bundle;
  private final BundleContext bundleContext;
  private final Version version;
  private final String root;
  private String name;

  public PentahoWebPackageImpl( Bundle bundle, String name, Version version, String root ) {
    this.bundle = bundle;
    this.bundleContext = bundle.getBundleContext();

    this.name = name;
    this.version = version;

    this.root = root;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public String getVersion() {
    return null;
  }

  @Override
  public String getContextPath() {
    return null;
  }

  @Override
  public void init() {
    // Register resource mapping in httpService whiteboard
    DefaultResourceMapping resourceMapping = new DefaultResourceMapping();
    resourceMapping.setAlias( "/" + name + "/" + version );
    resourceMapping.setPath( root );

    this.bundleContext.registerService( ResourceMapping.class.getName(), resourceMapping, null );
  }
}
