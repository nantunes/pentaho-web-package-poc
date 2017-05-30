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
package org.pentaho.webpackage.deployer.internal.osgi;

import org.apache.felix.fileinstall.ArtifactUrlTransformer;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;
import org.pentaho.webpackage.deployer.UrlHandler;
import org.pentaho.webpackage.deployer.UrlTransformer;

import java.util.Dictionary;
import java.util.Hashtable;

public class Activator implements BundleActivator {
  private UrlTransformer urlTransformer;
  private ServiceRegistration<ArtifactUrlTransformer> urlTransformerRegistration;

  private UrlHandler urlHandler;
  private ServiceRegistration<URLStreamHandlerService> urlHandlerRegistration;

  public void start( BundleContext bundleContext ) {
    urlTransformer = new UrlTransformer();
    urlTransformerRegistration = bundleContext.registerService( ArtifactUrlTransformer.class, urlTransformer, null );

    urlHandler = new UrlHandler();

    Dictionary<String, String> props = new Hashtable<>();
    props.put( URLConstants.URL_HANDLER_PROTOCOL, "pentaho-webpackage" );

    urlHandlerRegistration = bundleContext.registerService( URLStreamHandlerService.class, urlHandler, props );
  }

  public void stop( BundleContext bundleContext ) {
    if ( urlTransformerRegistration != null ) {
      urlTransformerRegistration.unregister();
      urlTransformerRegistration = null;
      urlTransformer = null;
    }

    if ( urlHandlerRegistration != null ) {
      urlHandlerRegistration.unregister();
      urlHandlerRegistration = null;
      urlHandler = null;
    }
  }
}
