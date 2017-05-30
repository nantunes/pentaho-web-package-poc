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
package org.pentaho.requirejs.internal.osgi;

import org.ops4j.pax.web.extender.whiteboard.ExtenderConstants;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.pentaho.requirejs.RequireJsConfigManager;
import org.pentaho.requirejs.RequireJsConfigServlet;

import javax.servlet.Servlet;
import java.util.Dictionary;
import java.util.Hashtable;

public class Activator implements BundleActivator {
  private RequireJsConfigManager requireJsConfigManager;
  private ServiceRegistration<Servlet> servletRegistration;

  public void start( BundleContext bundleContext ) {
    this.requireJsConfigManager = new RequireJsConfigManager();
    this.requireJsConfigManager.setBundleContext( bundleContext );
    try {
      this.requireJsConfigManager.init();
    } catch ( Exception e ) {
      this.requireJsConfigManager = null;
    }

    RequireJsConfigServlet requireJsConfigServlet = new RequireJsConfigServlet();
    requireJsConfigServlet.setContextRoot( "/" );
    requireJsConfigServlet.setManager( this.requireJsConfigManager );

    Dictionary<String, String> props = new Hashtable<>();
    props.put( ExtenderConstants.PROPERTY_ALIAS, "/requirejs-manager/requirejs-config.js" );

    this.servletRegistration = bundleContext.registerService( Servlet.class, requireJsConfigServlet, props );
  }

  public void stop( BundleContext bundleContext ) {
    if ( this.requireJsConfigManager != null ) {
      this.requireJsConfigManager.destroy();
      this.requireJsConfigManager = null;
    }

    if ( this.servletRegistration != null ) {
      this.servletRegistration.unregister();
      this.servletRegistration = null;
    }
  }
}
