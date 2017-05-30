/*
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright 2017 Pentaho Corporation. All rights reserved.
 */

package org.pentaho.webpackage.deployer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.json.simple.parser.JSONParser;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WebPackageURLConnection extends java.net.URLConnection {

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool( 5, r -> {
    Thread thread = Executors.defaultThreadFactory().newThread( r );
    thread.setDaemon( true );
    thread.setName( "WebjarsURLConnection pool" );
    return thread;
  } );
  private static final JSONParser parser = new JSONParser();
  private final Logger logger = LoggerFactory.getLogger( getClass() );
  Future<Void> transform_thread;

  public WebPackageURLConnection( URL url ) {
    super( url );
  }

  @Override
  public void connect() throws IOException {
  }

  @Override
  public InputStream getInputStream() throws IOException {
    try {
      final PipedOutputStream pipedOutputStream = new PipedOutputStream();
      PipedInputStream pipedInputStream = new PipedInputStream( pipedOutputStream );

      // making this here allows to fail with invalid URLs
      final java.net.URLConnection urlConnection = url.openConnection();
      urlConnection.connect();
      final InputStream originalInputStream = urlConnection.getInputStream();

      transform_thread = EXECUTOR.submit( new WebPackageTransformer( url, originalInputStream, pipedOutputStream ) );

      return pipedInputStream;
    } catch ( Exception e ) {
      logger.error( getURL().toString() + ": Error opening url" );

      throw new IOException( "Error opening url", e );
    }
  }

  private static class WebPackageTransformer implements Callable<Void> {
    private static final String DEBUG_MESSAGE_FAILED_WRITING =
        "Problem transferring Jar content, probably JarOutputStream was already closed.";

    private static final String MANIFEST_MF = "MANIFEST.MF";
    private static final String PACKAGE_JSON = "package.json";

    private static final int BYTES_BUFFER_SIZE = 4096;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    /* constructor information */
    private final URL url;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    //region transformation state

    /* artifact information */
    private JarOutputStream jarOutputStream;


    /* resource paths */
    private Path absoluteResourcesPath;

    private Path absoluteTempPath;

    //endregion

    WebPackageTransformer( URL url, InputStream inputStream, PipedOutputStream outputStream ) {
      this.url = url;

      this.inputStream = inputStream;
      this.outputStream = outputStream;
    }

    @Override
    public Void call() throws Exception {
      try {
        this.transform();
      } catch ( Exception e ) {
        logger.error( this.url.toString() + ": Error Transforming zip", e );

        this.outputStream.close();

        throw e;
      }

      return null;
    }

    private void transform() throws IOException {
      String webjarUrl = "";

      init();

      Manifest manifest;
      if ( this.url.getProtocol().equals( "jardir" ) || this.url.getProtocol().equals( "file" ) && this.url.getPath().endsWith( ".zip" ) ) {
        manifest = readFromZip();
      } else {
        manifest = readFromTgz();
      }

      this.jarOutputStream = new JarOutputStream( outputStream, manifest );

      Collection<File> scrFiles = FileUtils.listFiles(
          absoluteTempPath.toFile(),
          TrueFileFilter.INSTANCE,
          TrueFileFilter.INSTANCE
      );

      for ( File srcFile : scrFiles ) {
        final String relSrcFilePath = FilenameUtils.separatorsToUnix( absoluteTempPath.relativize( srcFile.toPath() ).toString() );

        copyFileToZip( jarOutputStream, relSrcFilePath, srcFile );
      }

      try {
        jarOutputStream.closeEntry();

        outputStream.flush();

        jarOutputStream.close();
      } catch ( IOException ioexception ) {
        logger.debug( webjarUrl + ": " + DEBUG_MESSAGE_FAILED_WRITING, ioexception );
      }

      try {
        FileUtils.deleteDirectory( absoluteTempPath.toFile() );
      } catch ( IOException ignored ) {
        // ignored
      }
    }

    private Manifest readFromZip() {
      Manifest manifest = getManifest( this.url.getPath().replace( "/", "_" ), "0.0.0" );

      ZipInputStream zipInputStream = new ZipInputStream( inputStream );

      try {
        ZipEntry entry;

        List<String> capabilities = new ArrayList<>();
        List<String> requirements = new ArrayList<>();

        while ( ( entry = zipInputStream.getNextEntry() ) != null ) {
          String name = entry.getName();

          if ( !entry.isDirectory() ) {
            File temporarySourceFile = null;
            BufferedOutputStream temporarySourceFileOutputStream = null;

            temporarySourceFile = new File( absoluteTempPath.toAbsolutePath() + File.separator + FilenameUtils.separatorsToSystem( name ) );
            temporarySourceFile.getParentFile().mkdirs();

            temporarySourceFileOutputStream = new BufferedOutputStream( new FileOutputStream( temporarySourceFile ) );

            byte[] bytes = new byte[BYTES_BUFFER_SIZE];
            int read;
            while ( ( read = zipInputStream.read( bytes ) ) != -1 ) {
              temporarySourceFileOutputStream.write( bytes, 0, read );
            }

            temporarySourceFileOutputStream.close();

            if ( name.endsWith( PACKAGE_JSON ) ) {
              Map<String, Object> packageJson = parseJsonPackage( new FileInputStream( temporarySourceFile ) );

              String moduleName = (String) packageJson.get( "name" );
              String moduleVersion = VersionParser.parseVersion( (String) packageJson.get( "version" ) ).toString();
              String root = name.replace( PACKAGE_JSON, "" );
              if ( root.endsWith( "/" ) ) {
                root = root.substring( 0, root.length() - 1 );
              }

              capabilities.add( "org.pentaho.webpackage;name=\"" + moduleName + "\";version:Version=\"" + moduleVersion + "\";root=\"/" + root + "\"" );

              if ( packageJson.containsKey( "dependencies" ) ) {
                HashMap<String, ?> deps = (HashMap<String, ?>) packageJson.get( "dependencies" );

                final Set<String> depsKeySet = deps.keySet();
                for ( String key : depsKeySet ) {
                  requirements.add( "org.pentaho.webpackage;filter:=\"(&(name=" + key + ")(version>=" + (String) deps.get( key ) + "))\"" );
                }
              }
            }
          }

          zipInputStream.closeEntry();
        }

        if ( !capabilities.isEmpty() ) {
          manifest.getMainAttributes()
              .put( new Attributes.Name( Constants.PROVIDE_CAPABILITY ), String.join( ", ", capabilities ) );
        }

        if ( !requirements.isEmpty() ) {
          manifest.getMainAttributes()
              .put( new Attributes.Name( Constants.REQUIRE_CAPABILITY ), String.join( ", ", requirements ) );
        }
      } catch ( IOException e ) {
        logger.debug( ": Pipe is closed, no need to continue." );
      } finally {
        try {
          zipInputStream.close();
        } catch ( IOException ioexception ) {
          logger.debug( ": Tried to close JarInputStream, but it was already closed.", ioexception );
        }
      }

      return manifest;
    }

    private Manifest readFromTgz() {
      Manifest manifest = getManifest( this.url.getPath().replace( "/", "_" ), "0.0.0" );

      TarArchiveInputStream tarGzInputStream = null;

      try {
        tarGzInputStream = new TarArchiveInputStream( new GzipCompressorInputStream( inputStream ) );

        List<String> capabilities = new ArrayList<>();
        List<String> requirements = new ArrayList<>();

        TarArchiveEntry entry;
        while ( ( entry = tarGzInputStream.getNextTarEntry() ) != null ) {
          String name = entry.getName();

          if ( !entry.isDirectory() ) {
            File temporarySourceFile = null;
            BufferedOutputStream temporarySourceFileOutputStream = null;

            temporarySourceFile = new File( absoluteTempPath.toAbsolutePath() + File.separator + FilenameUtils.separatorsToSystem( name ) );
            temporarySourceFile.getParentFile().mkdirs();

            temporarySourceFileOutputStream = new BufferedOutputStream( new FileOutputStream( temporarySourceFile ) );

            byte[] bytes = new byte[BYTES_BUFFER_SIZE];
            int read;
            while ( ( read = tarGzInputStream.read( bytes ) ) != -1 ) {
              temporarySourceFileOutputStream.write( bytes, 0, read );
            }

            temporarySourceFileOutputStream.close();

            if ( name.endsWith( PACKAGE_JSON ) ) {
              Map<String, Object> packageJson = parseJsonPackage( new FileInputStream( temporarySourceFile ) );

              String moduleName = (String) packageJson.get( "name" );
              String moduleVersion = VersionParser.parseVersion( (String) packageJson.get( "version" ) ).toString();
              String root = name.replace( PACKAGE_JSON, "" );
              if ( root.endsWith( "/" ) ) {
                root = root.substring( 0, root.length() - 1 );
              }

              capabilities.add( "org.pentaho.webpackage;name=\"" + moduleName + "\";version:Version=\"" + moduleVersion + "\";root=\"/" + root + "\"" );

              if ( packageJson.containsKey( "dependencies" ) ) {
                HashMap<String, ?> deps = (HashMap<String, ?>) packageJson.get( "dependencies" );

                final Set<String> depsKeySet = deps.keySet();
                for ( String key : depsKeySet ) {
                  requirements.add( "org.pentaho.webpackage;filter:=\"(&(name=" + key + ")(version>=" + (String) deps.get( key ) + "))\"" );
                }
              }
            }
          }
        }

        if ( !capabilities.isEmpty() ) {
          manifest.getMainAttributes()
              .put( new Attributes.Name( Constants.PROVIDE_CAPABILITY ), String.join( ", ", capabilities ) );
        }

        if ( !requirements.isEmpty() ) {
          manifest.getMainAttributes()
              .put( new Attributes.Name( Constants.REQUIRE_CAPABILITY ), String.join( ", ", requirements ) );
        }
      } catch ( IOException e ) {
        logger.debug( ": Pipe is closed, no need to continue." );
      } finally {
        try {
          if ( tarGzInputStream != null ) {
            tarGzInputStream.close();
          }
        } catch ( IOException ioexception ) {
          logger.debug( ": Tried to close JarInputStream, but it was already closed.", ioexception );
        }
      }

      return manifest;
    }

    public Map<String, Object> parseJsonPackage( InputStream inputStream ) {
      try {
        InputStreamReader inputStreamReader = new InputStreamReader( inputStream );
        BufferedReader bufferedReader = new BufferedReader( inputStreamReader );

        return (Map<String, Object>) parser.parse( bufferedReader );
      } catch ( Exception e ) {
        throw new RuntimeException( "Error opening package.json", e );
      }
    }

    private void init() throws IOException {
      this.absoluteResourcesPath = null;

      this.absoluteTempPath = Files.createTempDirectory( "PentahoWebPackageDeployer" );
    }

    private Manifest getManifest( String name, String version ) {
      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put( Attributes.Name.MANIFEST_VERSION, "1.0" );

      manifest.getMainAttributes()
          .put( new Attributes.Name( Constants.BUNDLE_MANIFESTVERSION ), "2" );

      manifest.getMainAttributes()
          .put( new Attributes.Name( Constants.BUNDLE_SYMBOLICNAME ), "pentaho-webpackage-" + name );
      manifest.getMainAttributes()
          .put( new Attributes.Name( Constants.BUNDLE_VERSION ), version );

      return manifest;
    }

    private void copyFileToZip( JarOutputStream zip, String entry, File file ) throws IOException {
      int bytesIn;
      byte[] readBuffer = new byte[BYTES_BUFFER_SIZE];

      FileInputStream inputStream = null;
      try {
        inputStream = new FileInputStream( file );

        ZipEntry zipEntry = new ZipEntry( entry );
        zip.putNextEntry( zipEntry );

        bytesIn = inputStream.read( readBuffer );
        while ( bytesIn != -1 ) {
          zip.write( readBuffer, 0, bytesIn );
          bytesIn = inputStream.read( readBuffer );
        }
      } finally {
        try {
          if ( inputStream != null ) {
            inputStream.close();
          }
        } catch ( IOException ignored ) {
          // ignored
        }
      }
    }

    private void addContentToZip( JarOutputStream zip, String entry, String content ) throws IOException {
      ZipEntry zipEntry = new ZipEntry( entry );
      zip.putNextEntry( zipEntry );
      zip.write( content.getBytes( "UTF-8" ) );
      zip.closeEntry();
    }

    /**
     * Created by nbaker on 11/25/14.
     */
    public static class VersionParser {
      private static Logger logger = LoggerFactory.getLogger( VersionParser.class );

      private static Version DEFAULT = new Version( 0, 0, 0 );
      private static Pattern VERSION_PAT = Pattern.compile( "([0-9]+)?(?:\\.([0-9]*)(?:\\.([0-9]*))?)?[\\.-]?(.*)" );
      private static Pattern CLASSIFIER_PAT = Pattern.compile( "[a-zA-Z0-9_\\-]+" );

      private VersionParser() throws InstantiationException {
        throw new InstantiationException( "Instances of this type are forbidden." );
      }

      public static Version parseVersion( String incomingVersion ) {
        if ( incomingVersion == null || incomingVersion.isEmpty() ) {
          return DEFAULT;
        }
        Matcher m = VERSION_PAT.matcher( incomingVersion );
        if ( !m.matches() ) {
          return DEFAULT;
        } else {
          String s_major = m.group( 1 );
          String s_minor = m.group( 2 );
          String s_patch = m.group( 3 );
          String classifier = m.group( 4 );
          Integer major = 0;
          Integer minor = 0;
          Integer patch = 0;

          if ( s_major != null && !s_major.isEmpty() ) {
            try {
              major = Integer.parseInt( s_major );
            } catch ( NumberFormatException e ) {
              logger.warn( "Major version part not an integer: " + s_major );
            }
          }

          if ( s_minor != null && !s_minor.isEmpty() ) {
            try {
              minor = Integer.parseInt( s_minor );
            } catch ( NumberFormatException e ) {
              logger.warn( "Minor version part not an integer: " + s_minor );
            }
          }

          if ( s_patch != null && !s_patch.isEmpty() ) {
            try {
              patch = Integer.parseInt( s_patch );
            } catch ( NumberFormatException e ) {
              logger.warn( "Patch version part not an integer: " + s_patch );
            }
          }

          if ( classifier != null ) {
            // classifiers cannot have a '.'
            classifier = classifier.replaceAll( "\\.", "_" );

            // Classifier characters must be in the following ranges a-zA-Z0-9_\-
            if ( !CLASSIFIER_PAT.matcher( classifier ).matches() ) {
              logger.warn( "Provided Classifier not valid for OSGI, ignoring" );
              classifier = null;
            }
          }

          if ( classifier != null ) {
            return new Version( major, minor, patch, classifier );
          } else {
            return new Version( major, minor, patch );
          }

        }
      }
    }
  }
}
