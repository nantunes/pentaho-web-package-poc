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

public abstract class PentahoWebPackageBundleAbstract implements PentahoWebPackageBundle {
  private long bundleId;
  private String name;

  @Override
  public long getBundleId() {
    return bundleId;
  }

  public void setBundleId( long bundleId ) {
    this.bundleId = bundleId;
  }

  @Override
  public String getName() {
    return name;
  }

  public void setName( String name ) {
    this.name = name;
  }

  @Override
  public void init() {
  }

  @Override
  public void destroy() {
  }
}
