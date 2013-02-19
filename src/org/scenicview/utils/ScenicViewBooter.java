/*
 * Copyright (c) 2012 Oracle and/or its affiliates.
 * All rights reserved. Use is subject to license terms.
 *
 * This file is available and licensed under the following license:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  - Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the distribution.
 *  - Neither the name of Oracle Corporation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.scenicview.utils;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;

import org.fxconnector.remote.FXConnectorFactory;
import org.fxconnector.remote.util.ScenicViewExceptionLogger;
import org.scenicview.ScenicView;

/**
 * 
 */
public class ScenicViewBooter {

    public static final String TOOLS_JAR_PATH_KEY = "attachPath";
    public static final String JFXRT_JAR_PATH_KEY = "jfxPath";

    private static boolean debug = false;

    private static final void debug(final String log) {
        if (debug) {
            System.out.println(log);
        }
    }

    public static void main(final String[] args) {
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-debug")) {
                    debug = true;

                }
            }
        }
        new ScenicViewBooter();
    }

    private void activateDebug() {
        FXConnectorFactory.setDebug(debug);
        ScenicView.setDebug(debug);
    }

    private static Properties properties;

    private ScenicViewBooter() {
        // first we check if the classes are already on the classpath
        final boolean[] checks = testClassPathRequirements();
        final boolean isAttachAPIAvailable = checks[0];
        final boolean isJFXAvailable = checks[1];
        
        if (isAttachAPIAvailable && isJFXAvailable) {
            // Launch ScenicView directly
            start(null);
        } else {
            // If we are here, the classes are not on the classpath.
            // First, we read the properties file to find previous entries
            properties = PropertiesUtils.getProperties();

            String attachPath = "";
            String jfxPath = "";

            boolean needAttachAPI = !isAttachAPIAvailable;
            boolean needJFXAPI = !isJFXAvailable;

            if (needAttachAPI) {
                // the tools.jar file
                // firstly we try the properties reference
                attachPath = properties.getProperty(TOOLS_JAR_PATH_KEY);
                needAttachAPI = !Utils.checkPath(attachPath);
                if (needAttachAPI) {
                    // If we can't get it from the properties file, we try to
                    // find it on the users operating system
                    attachPath = getToolsClassPath();
                    needAttachAPI = !Utils.checkPath(attachPath);
                }

                if (!needAttachAPI) {
                    updateClassPath(attachPath);
                }
            }

            if (needJFXAPI) {
                // the jfxrt.jar file
                // firstly we try the properties reference
                jfxPath = properties.getProperty("jfxPath");
                needJFXAPI = !Utils.checkPath(jfxPath);
                if (needJFXAPI) {
                    // If we can't get it from the properties file, we try to
                    // find it on the users operating system
                    jfxPath = getJFXClassPath();
                    needJFXAPI = !Utils.checkPath(jfxPath);
                }

                if (!needJFXAPI) {
                    updateClassPath(jfxPath);
                }
            }

            if (needAttachAPI || needJFXAPI) {

                /**
                 * This needs to be improved, in this situation we already have
                 * attachAPI but not because it was saved in the file, try to
                 * fill the path by finding it
                 */
                if (!needAttachAPI && (attachPath == null || attachPath.equals(""))) {
                    attachPath = getToolsClassPath();
                }
                /**
                 * This needs to be improved, in this situation we already have
                 * jfxAPI but not because it was saved in the file, try to fill
                 * the path by finding it
                 */
                if (!needJFXAPI && (jfxPath == null || jfxPath.equals(""))) {
                    jfxPath = getJFXClassPath();
                }

                final String _attachPath = attachPath;
                final String _jfxPath = jfxPath;
                if (Utils.isMac()) {
                    System.setProperty("javafx.macosx.embedded", "true");
                }
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        // instatiate the ClassPathDialog at startup to
                        // workaround
                        // issues
                        // if it is created after JavaFX has started.
                        SwingClassPathDialog.init();
                    }
                });

                SwingClassPathDialog.showDialog(_attachPath, _jfxPath, true, new PathChangeListener() {
                    @Override public void onPathChanged(final Map<String, URI> map) {
                        final URI toolsPath = map.get(PathChangeListener.TOOLS_JAR_KEY);
                        final URI jfxPath = map.get(PathChangeListener.JFXRT_JAR_KEY);
                        updateClassPath(toolsPath);
                        updateClassPath(jfxPath);
                        properties.setProperty(TOOLS_JAR_PATH_KEY, toolsPath.toASCIIString());
                        properties.setProperty(JFXRT_JAR_PATH_KEY, jfxPath.toASCIIString());
                        PropertiesUtils.saveProperties();
                        SwingClassPathDialog.hideDialog();
                        new Thread() {
                            @Override public void run() {
                                ScenicViewBooter.this.start(toolsPath);
                            }
                        }.start();

                    }
                });
            } else {
                start(Utils.toURI(attachPath));
            }
        }
    }

    private void start(final URI attachPath) {
        activateDebug();
        patchAttachLibrary(attachPath);
        RemoteScenicViewLauncher.start();
    }

    private void patchAttachLibrary(final URI attachPath) {

        if (attachPath != null /*&& Utils.isWindows()*/ && new File(attachPath).exists()) {
            final File jdkHome = new File(attachPath).getParentFile().getParentFile();
            try {
                System.loadLibrary("attach");
            } catch (final UnsatisfiedLinkError e) {
                /**
                 * Try to set or modify java.library.path
                 */
                final String path = jdkHome.getAbsolutePath() + 
                                    File.pathSeparator + 
                                    "jre" + 
                                    File.pathSeparator + 
                                    "bin;" + 
                                    System.getProperty("java.library.path");
                System.setProperty("java.library.path", path);
                try {
                    /**
                     * This code is need for reevaluating the library path
                     */
                    final Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                    fieldSysPath.setAccessible(true);
                    fieldSysPath.set(null, null);
                    System.loadLibrary("attach");
                } catch (final Throwable e2) {
                    ExceptionLogger.submitException(e2);
                    System.out.println("Error while trying to put attach.dll in path");
                }
            }
        }
    }

    private String getJFXClassPath() {
        final List<String> results = JfxrtFinder.findJfxrt();
        // // see if we can find JavaFX at the runtime path
        // String path = System.getProperty("javafx.runtime.path");
        // path = path == null ? "" : path;

        for (final String path : results) {
            if (path != null && new File(path).exists()) {
                // properties.setProperty(JFXRT_JAR_PATH_KEY, path);
                return path;
            }
        }

        return "";
    }

    private String getToolsClassPath() {
        final String javaHome = System.getProperty("java.home");
        if (!javaHome.contains("jdk")) {
            // JOptionPane.showMessageDialog(null, "No JDK found");
            System.out.println("Error: No JDK found on system");
            return null;
        }

        // This points to, for example, "C:\Program Files
        // (x86)\Java\jdk1.6.0_30\jre"
        // This is one level too deep. We want to pop up and then go into the
        // lib directory to find tools.jar
        debug("JDK found at: " + javaHome);

        File toolsJar = new File(javaHome + "/../lib/tools.jar");
        if (!toolsJar.exists()) {
            // JOptionPane.showMessageDialog(null, "No tools.jar found at\n" +
            // toolsJar);
            boolean found = false;
            if (Utils.isMac()) {
                toolsJar = getToolsClassPathOnMAC();
                if (toolsJar != null) {
                    found = true;
                }
            }
            if (!found) {
                System.out.println("Error: Can not find tools.jar on system - disabling VM lookup");
                return null;
            }
        }

        final String path = toolsJar.getAbsolutePath();
        // properties.setProperty(TOOLS_JAR_PATH_KEY, path);

        return path;
    }

    private void updateClassPath(final String uriPath) {
        updateClassPath(Utils.toURI(uriPath));
    }

    private void updateClassPath(final URI uri) {
        try {
            final URL url = uri.toURL();
            debug("Adding to classpath: " + url);
            ClassPathHacker.addURL(url);
        } catch (final IOException ex) {
            ExceptionLogger.submitException(ex);
        }
    }

    private boolean[] testClassPathRequirements() {
        boolean isAttachAPIAvailable = false;
        boolean isJFXAvailable = false;

        // Test if we can load a class from tools.jar
        try {
            Class.forName("com.sun.tools.attach.AttachNotSupportedException").newInstance();
            isAttachAPIAvailable = true;
        } catch (final Exception e) {
            debug("Java Attach API was not found");
        }

        // Test if we can load a class from jfxrt.jar
        try {
            Class.forName("javafx.beans.property.SimpleBooleanProperty").newInstance();
            isJFXAvailable = true;
        } catch (final Exception e) {
            debug("JavaFX API was not found");
        }

        return new boolean[] { isAttachAPIAvailable, isJFXAvailable };
    }

    private File getToolsClassPathOnMAC() {
        /**
         * Apparently tools.jar can be on: a) /Library/Java/JavaVirtualMachines/
         * b) System/Library/Java/JavaVirtualMachines/
         */
        File toolsFile = getToolsClassPathOnMAC(new File("/Library/Java/JavaVirtualMachines/"));
        if (toolsFile == null) {
            toolsFile = getToolsClassPathOnMAC(new File("/System/Library/Java/JavaVirtualMachines/"));
        }
        return toolsFile;
    }

    private File getToolsClassPathOnMAC(final File jvmsRoot) {
        debug("Testing tools classPath on MAC:" + jvmsRoot.getAbsolutePath());
        final File[] jdks = jvmsRoot.listFiles(new FileFilter() {

            @Override public boolean accept(final File dir) {
                return (dir.isDirectory() && dir.getName().indexOf(".jdk") != -1);
            }
        });
        for (int i = 0; i < jdks.length; i++) {
            debug("Valid JDKs:" + jdks[i].getName());
        }
        final String javaVersion = System.getProperty("java.version");
        /**
         * If we are using JDK6 using classes.jar otherwise tools.jar
         */
        if (javaVersion.indexOf("1.6") != -1) {
            for (int i = 0; i < jdks.length; i++) {
                if (jdks[i].getName().indexOf("1.6") != -1) {
                    final File classesFile = new File(jdks[i], "Contents/Classes/classes.jar");
                    if (classesFile.exists()) {
                        debug("Classes file found on MAC in:" + classesFile.getAbsolutePath());
                        return classesFile;
                    }
                }
            }
        }
        if (javaVersion.indexOf("1.7") != -1) {
            for (int i = 0; i < jdks.length; i++) {
                if (jdks[i].getName().indexOf("1.7") != -1) {
                    final File toolsFile = new File(jdks[i], "Contents/Home/lib/tools.jar");
                    if (toolsFile.exists()) {
                        debug("Tools file found on MAC in:" + toolsFile.getAbsolutePath());
                        return toolsFile;
                    }
                }
            }
        }
        return null;
    }
}