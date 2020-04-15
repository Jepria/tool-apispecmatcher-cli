package org.jepria.tools.apispecmatcher.cli;

import org.jepria.tools.apispecmatcher.core.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public class Main {

  private static PrintStream out = System.out;

  public static void main(String[] args) {

    if (args != null) {
      List<String> argList = Arrays.asList(args);

      final Runner runner;

      if (argList.get(0).equals("--maven-project") && argList.size() == 2) {
        // absolute path to the maven project (directory with the src folder)

        String mavenProjectArg = argList.get(1);

        try {
          runner = new Runner(mavenProjectArg);
        } catch (Runner.PrepareException e) {
          for (String message : e.getMessages()) {
            out.println(message);
          }
          return;
        }

      } else {

        final List<String> apiSpecPaths = new ArrayList<>();
        final List<String> jaxrsAdapters = new ArrayList<>();
        final List<String> projectClasspathClassDirPaths = new ArrayList<>();
        final List<String> projectClasspathJarDirPaths = new ArrayList<>();
        final List<String> projectClasspathJarPaths = new ArrayList<>();
        final List<String> projectSourceRootDirPaths = new ArrayList<>();

        // read cmd args
        for (int i = 0; i < argList.size(); i++) {

          if (argList.get(i).equals("--api-specs") && i < argList.size() - 1) {
            // coma separated list of absolute paths to the api spec (e.g. swagger.json) files that need to be matched
            i++;
            String apiSpecPathsArg = argList.get(i);
            apiSpecPaths.addAll(Arrays.asList(apiSpecPathsArg.split("\\s*;\\s*")));
          } else if (argList.get(i).equals("--jaxrs-adapters") && i < argList.size() - 1) {
            // coma separated list of qualified classnames of the jaxrs adapters that need to be matched
            i++;
            String jaxrsAdapterPathsArg = argList.get(i);
            jaxrsAdapters.addAll(Arrays.asList(jaxrsAdapterPathsArg.split("\\s*;\\s*")));
          } else if (argList.get(i).equals("--project-classpath-class-dirs") && i < argList.size() - 1) {
            // coma separated list of absolute paths to the class file hierarchy roots (e.g. target/classes dir in maven), required for loading jaxrs-adapter classes
            i++;
            String projectClasspathClassDirsArg = argList.get(i);
            projectClasspathClassDirPaths.addAll(Arrays.asList(projectClasspathClassDirsArg.split("\\s*;\\s*")));
          } else if (argList.get(i).equals("--project-classpath-jars-dirs") && i < argList.size() - 1) {
            // coma separated list of absolute paths to the jar collection dirs (e.g. WEB-INF/lib dir in an exploded war), required for loading jaxrs-adapter classes
            i++;
            String projectClasspathJarDirsArg = argList.get(i);
            projectClasspathJarDirPaths.addAll(Arrays.asList(projectClasspathJarDirsArg.split("\\s*;\\s*")));
          } else if (argList.get(i).equals("--project-classpath-jars") && i < argList.size() - 1) {
            // coma separated list of absolute paths to the jar files, required for loading jaxrs-adapter classes
            i++;
            String projectClasspathJarsArg = argList.get(i);
            projectClasspathJarPaths.addAll(Arrays.asList(projectClasspathJarsArg.split("\\s*;\\s*")));
          } else if (argList.get(i).equals("--project-source-root-dirs") && i < argList.size() - 1) {
            // coma separated list of absolute paths to the source file hierarchy roots, corresponding to the class file hierarchy roots provided
            i++;
            String projectSourceRootDirsArg = argList.get(i);
            projectSourceRootDirPaths.addAll(Arrays.asList(projectSourceRootDirsArg.split("\\s*;\\s*")));
          }
        }

        try {
          runner = new Runner(apiSpecPaths, jaxrsAdapters, projectClasspathClassDirPaths, projectClasspathJarDirPaths, projectClasspathJarPaths, projectSourceRootDirPaths);
        } catch (Runner.PrepareException e) {
          for (String message : e.getMessages()) {
            out.println(message);
          }
          return;
        }

      }



      runner.run();

    } else {
      out.println("No arguments provided");
      return;
    }
  }

  protected static class Runner implements Runnable {

    final List<File> apiSpecs = new ArrayList<>();
    final List<String> jaxrsAdapters = new ArrayList<>();
    final List<File> projectClasspathClassDirs = new ArrayList<>();
    final List<File> projectClasspathJarDirs = new ArrayList<>();
    final List<File> projectClasspathJars = new ArrayList<>();
    final List<File> projectSourceRootDirs = new ArrayList<>();

    public static class PrepareException extends Exception {
      private final List<String> messages;
      public PrepareException(List<String> messages) {
        this.messages = messages;
      }
      public List<String> getMessages() {
        return messages;
      }
    }

    public Runner(String mavenProjectArg) throws PrepareException {

      boolean failed = false;
      List<String> failMessages = new ArrayList<>();

      Path mavenProject = Paths.get(mavenProjectArg);
      if (!Files.exists(mavenProject)) {
        failed = true;
        failMessages.add("Incorrect file path [" + mavenProjectArg + "]: file does not exist");
      } else if (!Files.isDirectory(mavenProject)) {
        failed = true;
        failMessages.add("Incorrect file path [" + mavenProjectArg + "]: not a directory");

      } else {

        Path sourceRoot = mavenProject.resolve("src");
        Path javaSourceRoot = sourceRoot.resolve("main/java");

        try {
          Files.walk(sourceRoot)
                  .filter(path -> Files.isRegularFile(path) && path.toFile().getName().equals("swagger.json"))
                  .forEach(path -> apiSpecs.add(path.toFile()));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        try {
          Files.walk(javaSourceRoot)
                  .filter(path -> Files.isRegularFile(path) && path.toFile().getName().endsWith("JaxrsAdapter.java"))
                  .forEach(path -> {
                    Path relative = javaSourceRoot.relativize(path);
                    String str = relative.toString();
                    String classname = str.substring(0, str.length() - ".java".length()).replaceAll("/|\\\\", ".");
                    jaxrsAdapters.add(classname);
                  });
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        Path target = mavenProject.resolve("target");
        if (!Files.isDirectory(target)) {
          failed = true;
          failMessages.add("The maven 'target' directory expected on the path [" + target.toString() + "]");
        } else {
          File[] wars = target.toFile().listFiles(file -> file.getName().endsWith(".war"));
          if (wars == null || wars.length == 0) {
            failed = true;
            failMessages.add("No .war files found in the folder [" + target.toString() + "]");
          } else {
            File war = wars[0];
            String warName = war.getName().substring(0, war.getName().length() - ".war".length());
            File warDirectory = new File(war.getParentFile(), warName); // war directory has the same name as .war file has
            File jarDir = new File(warDirectory, "WEB-INF/lib");
            if (!Files.isDirectory(jarDir.toPath())) {
              failed = true;
              failMessages.add("The jar directory expected on the path [" + jarDir.toString() + "]");
            } else {
              projectClasspathJarDirs.add(jarDir);
            }
          }
        }

        if (!Files.isDirectory(javaSourceRoot)) {
          failed = true;
          failMessages.add("The maven source root directory expected on the path [" + javaSourceRoot.toString() + "]");
        } else {
          projectSourceRootDirs.add(javaSourceRoot.toFile());
        }
      }

      // workaround: servlet-api.jar is required for the classloader
      // TODO read the dependency from the pom.xml of the project?
      String binHomeEnvVar = System.getenv("BIN_HOME");
      if (binHomeEnvVar == null) {
        failed = true;
        failMessages.add("BIN_HOME env variable not defined, but required to access servlet-api.jar");
      } else {
        Path binHome = Paths.get(binHomeEnvVar);

        File javaxServletApiDir = binHome.resolve("build/javax/servlet/servlet-api").toFile();
        File tomcatServletApiDir = binHome.resolve("build/org/apache/tomcat/tomcat-servlet-api").toFile();
        
        File servletApiJar = null;

        {
          // lookup javax servlet-api
          if (javaxServletApiDir.isDirectory()) {
            File[] versions = javaxServletApiDir.listFiles();
            if (versions != null) {
              TreeSet<String> versionSet = new TreeSet<>();
              for (File version : versions) {
                if (version.isDirectory()) {
                  versionSet.add(version.getName());
                }
              }
              String lastVersion = versionSet.last();
              File versionDir = new File(javaxServletApiDir, lastVersion);
              File[] jars = versionDir.listFiles();
              if (jars != null) {
                for (File jar : jars) {
                  if (jar.getName().endsWith(".jar")) {
                    servletApiJar = jar;
                    break;
                  }
                }
              }
            }
          }
        }

        if (servletApiJar == null) {
          // lookup tomcat servlet-api
          if (tomcatServletApiDir.isDirectory()) {
            File[] versions = tomcatServletApiDir.listFiles();
            if (versions != null) {
              TreeSet<String> versionSet = new TreeSet<>();
              for (File version : versions) {
                if (version.isDirectory()) {
                  versionSet.add(version.getName());
                }
              }
              String lastVersion = versionSet.last();
              File versionDir = new File(tomcatServletApiDir, lastVersion);
              File[] jars = versionDir.listFiles();
              if (jars != null) {
                for (File jar : jars) {
                  if (jar.getName().endsWith(".jar")) {
                    servletApiJar = jar;
                    break;
                  }
                }
              }
            }
          }
        }

        if (servletApiJar == null) {
          failed = true;
          failMessages.add("No servlet-api.jar found on paths [" + javaxServletApiDir + "] and [" + tomcatServletApiDir + "]");
        } else {
          projectClasspathJars.add(servletApiJar);
        }
      }

      if (failed) {
        throw new PrepareException(failMessages);

      } else {
        // log everything
        out.println("The following components discovered in the project [" + mavenProjectArg + "]:");
        out.println("apiSpecs: " + apiSpecs);
        out.println("jaxrsAdapters: " + jaxrsAdapters);
        out.println("projectClasspathClassDirs: " + projectClasspathClassDirs);
        out.println("projectClasspathJarDirs: " + projectClasspathJarDirs);
        out.println("projectClasspathJars: " + projectClasspathJars);
        out.println("projectSourceRootDirs: " + projectSourceRootDirs);
      }
    }

    public Runner(List<String> apiSpecPaths,
                  List<String> jaxrsAdapters,
                  List<String> projectClasspathClassDirPaths,
                  List<String> projectClasspathJarDirPaths,
                  List<String> projectClasspathJarPaths,
                  List<String> projectSourceRootDirPaths) throws PrepareException {

      boolean failed = false;
      List<String> failMessages = new ArrayList<>();

      for (String pathStr: apiSpecPaths) {
        Path path = null;
        try {
          path = Paths.get(pathStr);
        } catch (Throwable e) {
          e.printStackTrace(); // TODO
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: resolve exception");
        }
        if (!Files.exists(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: file does not exist");
        } else if (!Files.isRegularFile(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: not a regular file");
        } else {
          apiSpecs.add(path.toFile());
        }
      }

      this.jaxrsAdapters.addAll(jaxrsAdapters);

      for (String pathStr: projectClasspathClassDirPaths) {
        Path path = null;
        try {
          path = Paths.get(pathStr);
        } catch (Throwable e) {
          e.printStackTrace(); // TODO
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: resolve exception");
        }
        if (!Files.exists(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: file does not exist");
        } else if (!Files.isDirectory(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: not a directory");
        } else {
          projectClasspathClassDirs.add(path.toFile());
        }
      }

      for (String pathStr: projectClasspathJarDirPaths) {
        Path path = null;
        try {
          path = Paths.get(pathStr);
        } catch (Throwable e) {
          e.printStackTrace(); // TODO
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: resolve exception");
        }
        if (!Files.exists(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: file does not exist");
        } else if (!Files.isDirectory(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: not a directory");
        } else {
          projectClasspathJarDirs.add(path.toFile());
        }
      }

      for (String pathStr: projectClasspathJarPaths) {
        Path path = null;
        try {
          path = Paths.get(pathStr);
        } catch (Throwable e) {
          e.printStackTrace(); // TODO
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: resolve exception");
        }
        if (!Files.exists(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: file does not exist");
        } else if (!Files.isRegularFile(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: not a regular file");
        } else {
          projectClasspathJars.add(path.toFile());
        }
      }

      for (String pathStr: projectSourceRootDirPaths) {
        Path path = null;
        try {
          path = Paths.get(pathStr);
        } catch (Throwable e) {
          e.printStackTrace(); // TODO
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: resolve exception");
        }
        if (!Files.exists(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: file does not exist");
        } else if (!Files.isDirectory(path)) {
          failed = true;
          failMessages.add("Incorrect file path [" + pathStr + "]: not a directory");
        } else {
          projectSourceRootDirs.add(path.toFile());
        }
      }

      if (failed) {
        throw new PrepareException(failMessages);
      }
    }

    @Override
    public void run() {
      try {
        // extract methods from resources
        List<Method> apiSpecMethods;
        List<Method> jaxrsMethods;
        {
          apiSpecMethods = new ArrayList<>();
          ApiSpecMethodExtractorJson ext1 = new ApiSpecMethodExtractorJson();
          for (File f: apiSpecs) {
            List<ApiSpecMethodExtractorJson.ExtractedMethod> apiSpecMethodsForResource;
            try (Reader r = new FileReader(f)) {
              apiSpecMethodsForResource = ext1.extract(r);
            }
            for (ApiSpecMethodExtractorJson.ExtractedMethod method: apiSpecMethodsForResource) {
              apiSpecMethods.add(method.method);
            }
          }
          jaxrsMethods = new ArrayList<>();

          JaxrsMethodExtractorCompiled ext2;
          {
            List<File> jars = new ArrayList<>();
            for (File dir: projectClasspathJarDirs) {
              File[] jars0 = dir.listFiles(file -> file.getName().endsWith(".jar"));
              if (jars0 != null) {
                jars.addAll(Arrays.asList(jars0));
              }
            }
            jars.addAll(projectClasspathJars);

            ext2 = new JaxrsMethodExtractorCompiled(projectClasspathClassDirs, jars, projectSourceRootDirs);
          }

          for (String r : jaxrsAdapters) {
            List<JaxrsMethodExtractorCompiled.ExtractedMethod> jaxrsMethodsForResource = ext2.extract(r);

            for (JaxrsMethodExtractorCompiled.ExtractedMethod method: jaxrsMethodsForResource) {

              // show warnings
              {
                if (method.features.remove(JaxrsMethodExtractorCompiled.ExtractedMethod.Features.DYNAMIC__TYPE_UNDECLARED)) {
                  // do nothing
                }
                if (method.features.remove(JaxrsMethodExtractorCompiled.ExtractedMethod.Features.STATIC__NO_SOURCE_TREE)) {
                  // do nothing (must have been already processed above)
                }
                if (method.features.remove(JaxrsMethodExtractorCompiled.ExtractedMethod.Features.STATIC__NO_SOURCE_FILE)) {
                  System.out.println("WARN: " + r + ": @" + method.method.httpMethod() + "_" + method.method.path() + ":");
                  System.out.println("  No source file found for the class, unable to determine static response body type.");
                }
                if (method.features.remove(JaxrsMethodExtractorCompiled.ExtractedMethod.Features.STATIC__NO_SOURCE_METHOD)) {
                  System.out.println("WARN: " + r + ": @" + method.method.httpMethod() + "_" + method.method.path() + ":");
                  System.out.println("  No such method found in the source file, unable to determine static response body type. " +
                          "The sources might have changed since the last compilation, try to recompile it.");
                }
                if (method.features.remove(JaxrsMethodExtractorCompiled.ExtractedMethod.Features.STATIC__VARIABLE_UNDECLARED)) {
                  System.out.println("WARN: " + r + ": @" + method.method.httpMethod() + "_" + method.method.path() + ":");
                  System.out.println("  No 'responseBody' variable declaration found in the method body, " +
                          "unable to determine static response body type.");
                }
                // check all features consumed
                if (method.features.size() > 0) {
                  throw new IllegalStateException("All features must be consumed. Remained: " + method.features);
                }
              }

              // add method
              jaxrsMethods.add(method.method);
            }
          }
        }


        Matcher.MatchParams params = new Matcher.MatchParams(apiSpecMethods, jaxrsMethods);
        Matcher.MatchResult matchResult = new MatcherImpl().match(params);

        if (matchResult.nonDocumentedMethods != null && !matchResult.nonDocumentedMethods.isEmpty() ||
                matchResult.nonImplementedMethods != null && !matchResult.nonImplementedMethods.isEmpty()) {
          System.out.println("Match failed");

          if (matchResult.nonDocumentedMethods != null && !matchResult.nonDocumentedMethods.isEmpty()) {
            for (Method nonDocumentedMethod: matchResult.nonDocumentedMethods) {
              System.out.println("Non-documented method at " + nonDocumentedMethod.location().asString() + ": " + nonDocumentedMethod.asString());
            }
          }
          if (matchResult.nonImplementedMethods != null && !matchResult.nonImplementedMethods.isEmpty()) {
            for (Method nonImplementedMethod: matchResult.nonImplementedMethods) {
              System.out.println("Non-implemented method at " + nonImplementedMethod.location().asString() + ": " + nonImplementedMethod.asString());
            }
          }
        } else {
          System.out.println("Match succeeded");
        }

      } catch (Throwable e) { throw new RuntimeException(e); }
    }
  }
}
