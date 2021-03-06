package org.jepria.tools.apispecmatcher.cli;

import org.jepria.tools.apispecmatcher.core.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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

      List<File> jaxrsAdapterSources = new ArrayList<>();

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
                    jaxrsAdapterSources.add(path.toFile());
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
        out.println("jaxrsAdapter sources: " + jaxrsAdapterSources);
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

        boolean success = true;

        class ApiSpecMethodWithLocation {
          public SpecMethod method;
          // original File
          public File location;

          public String asString() {
            return location.getAbsolutePath() + ": " + method.httpMethod() + " " + method.path();
          }

          @Override
          public String toString() {
            return asString();
          }

        }

        class JaxrsMethodWithLocation {
          public JaxrsMethod method;
          // canonical classname of the container class
          public String location;

          public String asString() {
            return location + ": " + method.httpMethod() + " " + method.path();
          }

          @Override
          public String toString() {
            return asString();
          }
        }

        List<ApiSpecMethodWithLocation> apiSpecMethods;
        {
          apiSpecMethods = new ArrayList<>();
          ApiSpecMethodExtractorJson ext1 = new ApiSpecMethodExtractorJson();
          for (File f : apiSpecs) {
            List<SpecMethod> apiSpecMethodsForResource;
            try (Reader r = new FileReader(f)) {
              apiSpecMethodsForResource = ext1.extract(r);
            }
            for (SpecMethod m: apiSpecMethodsForResource) {
              ApiSpecMethodWithLocation apiSpecMethod = new ApiSpecMethodWithLocation();
              apiSpecMethod.method = m;
              apiSpecMethod.location = f;
              apiSpecMethods.add(apiSpecMethod);
            }
          }
        }

        List<JaxrsMethodWithLocation> jaxrsMethods;
        {
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

          for (String classname : jaxrsAdapters) {
            List<JaxrsMethod> jaxrsMethodsForResource = ext2.extract(classname);
            for (JaxrsMethod m: jaxrsMethodsForResource) {
              JaxrsMethodWithLocation jaxrsMethod = new JaxrsMethodWithLocation();
              jaxrsMethod.method = m;
              jaxrsMethod.location = classname;
              jaxrsMethods.add(jaxrsMethod);
            }
          }
        }

        // create method map and warn none or multiple mappings
        class MethodMapping {
          public ApiSpecMethodWithLocation apiSpecMethod;
          public JaxrsMethodWithLocation jaxrsMethod;
        }
        List<MethodMapping> methodMappings;
        {
          methodMappings = new ArrayList<>();
          MethodMapper mapper = new MethodMapperImpl();

          {
            // straight check
            Iterator<ApiSpecMethodWithLocation> it = apiSpecMethods.iterator();
            while (it.hasNext()) {
              ApiSpecMethodWithLocation apiSpecMethod = it.next();
              List<JaxrsMethodWithLocation> mappings = new ArrayList<>();
              for (JaxrsMethodWithLocation jaxrsMethod : jaxrsMethods) {
                if (mapper.map(apiSpecMethod.method, jaxrsMethod.method)) {
                  mappings.add(jaxrsMethod);
                }
              }
              if (mappings.size() == 0) {
                System.out.println("FAIL: no Jaxrs method found for the ApiSpec method "
                        + apiSpecMethod.asString());
                success = false;
                it.remove();

              } else if (mappings.size() > 1) {
                System.out.println("FAIL: multiple Jaxrs methods found for the ApiSpec method "
                        + apiSpecMethod.asString());
                for (JaxrsMethodWithLocation mapping : mappings) {
                  System.out.println("  " + mapping.asString());
                }

                success = false;
                it.remove();
                for (JaxrsMethodWithLocation mapping : mappings) {
                  jaxrsMethods.remove((JaxrsMethodWithLocation) mapping);
                }

              } else {
                // single match, will be captured after the reverse check
              }
            }
          }

          {
            // reverse check
            Iterator<JaxrsMethodWithLocation> it = jaxrsMethods.iterator();
            while (it.hasNext()) {
              JaxrsMethodWithLocation jaxrsMethod = it.next();
              List<ApiSpecMethodWithLocation> mappings = new ArrayList<>();
              for (ApiSpecMethodWithLocation apiSpecMethod : apiSpecMethods) {
                if (mapper.map(apiSpecMethod.method, jaxrsMethod.method)) {
                  mappings.add(apiSpecMethod);
                }
              }
              if (mappings.size() == 0) {
                System.out.println("FAIL: no ApiSpec method found for the Jaxrs method "
                        + jaxrsMethod.asString());

                success = false;
                it.remove();

              } else if (mappings.size() > 1) {
                System.out.println("FAIL: multiple ApiSpec methods found for the Jaxrs method "
                        + jaxrsMethod.asString());
                for (ApiSpecMethodWithLocation mapping : mappings) {
                  System.out.println("  " + mapping.asString());
                }

                success = false;
                it.remove();
                for (ApiSpecMethodWithLocation mapping : mappings) {
                  jaxrsMethods.remove((ApiSpecMethodWithLocation) mapping);
                }

              } else {
                // single mapping found
                MethodMapping mm = new MethodMapping();
                mm.apiSpecMethod = mappings.get(0);
                mm.jaxrsMethod = jaxrsMethod;
                methodMappings.add(mm);
              }
            }
          }
        }


        // warn about static response body type extraction statuses
        List<JaxrsMethodWithLocation> jaxrsMethodsNoSourceTree = new ArrayList<>();
        for (MethodMapping mm: methodMappings) {
          if (mm.apiSpecMethod.method.responseBodySchema() != null &&
                  mm.jaxrsMethod.method.responseBodySchemaExtractionStatus() == JaxrsMethod.ResponseBodySchemaExtractionStatus.STATIC_NO_SOURCE_TREE) {
            jaxrsMethodsNoSourceTree.add(mm.jaxrsMethod);
          }
        }
        if (!jaxrsMethodsNoSourceTree.isEmpty()) {
          // such a pretty printing ...
          if (jaxrsMethodsNoSourceTree.size() == 1) {
            System.out.println("WARN: For the following method, the response body type could be determined " +
                    "from the sources, but actually no source tree provided:");
            System.out.println("  " + jaxrsMethodsNoSourceTree.get(0).asString());
          } else {
            System.out.println("WARN: For the following methods, the response body type could be determined " +
                    "from the sources, but actually no source tree provided:");
            for (JaxrsMethodWithLocation jm: jaxrsMethodsNoSourceTree) {
              System.out.println("  " + jm.asString());
            }
          }

        } else {
          // only makes sense if no STATIC_NO_SOURCE_TREE status found
          List<JaxrsMethodWithLocation> jaxrsMethodsNoSourceFile = new ArrayList<>();
          for (MethodMapping mm: methodMappings) {
            if (mm.apiSpecMethod.method.responseBodySchema() != null &&
                    mm.jaxrsMethod.method.responseBodySchemaExtractionStatus() == JaxrsMethod.ResponseBodySchemaExtractionStatus.STATIC_NO_SOURCE_FILE) {
              jaxrsMethodsNoSourceFile.add(mm.jaxrsMethod);
            }
          }
          if (!jaxrsMethodsNoSourceFile.isEmpty()) {
            // such a pretty printing ...
            if (jaxrsMethodsNoSourceFile.size() == 1) {
              System.out.println("WARN: For the following method, the response body type could be determined " +
                      "from the source file, but actually no source found for the class:");
              System.out.println("  " + jaxrsMethodsNoSourceFile.get(0).asString());
            } else {
              System.out.println("WARN: For the following methods, the response body type could be determined " +
                      "from the source file, but actually no source found for the class:");
              for (JaxrsMethodWithLocation jm: jaxrsMethodsNoSourceFile) {
                System.out.println("  " + jm.asString());
              }
            }
          }

          List<JaxrsMethodWithLocation> jaxrsMethodsNoSourceMethod = new ArrayList<>();
          for (MethodMapping mm: methodMappings) {
            if (mm.apiSpecMethod.method.responseBodySchema() != null &&
                    mm.jaxrsMethod.method.responseBodySchemaExtractionStatus() == JaxrsMethod.ResponseBodySchemaExtractionStatus.STATIC_NO_SOURCE_METHOD) {
              jaxrsMethodsNoSourceMethod.add(mm.jaxrsMethod);
            }
          }
          if (!jaxrsMethodsNoSourceMethod.isEmpty()) {
            // such a pretty printing ...
            if (jaxrsMethodsNoSourceMethod.size() == 1) {
              System.out.println("WARN: For the following method, the response body type could be determined " +
                      "from the source file, but actually no such method found in the source file " +
                      "(try to recompile the project):");
              System.out.println("  " + jaxrsMethodsNoSourceMethod.get(0).asString());
            } else {
              System.out.println("WARN: For the following methods, the response body type could be determined " +
                      "from the source file, but actually no such method found in the source file " +
                      "(try to recompile the project):");
              for (JaxrsMethodWithLocation jm: jaxrsMethodsNoSourceMethod) {
                System.out.println("  " + jm.asString());
              }
            }
          }

          List<JaxrsMethodWithLocation> jaxrsMethodsNoVarDecl = new ArrayList<>();
          for (MethodMapping mm: methodMappings) {
            if (mm.apiSpecMethod.method.responseBodySchema() != null &&
                    mm.jaxrsMethod.method.responseBodySchemaExtractionStatus() == JaxrsMethod.ResponseBodySchemaExtractionStatus.STATIC_VARIABLE_UNDECLARED) {
              jaxrsMethodsNoVarDecl.add(mm.jaxrsMethod);
            }
          }
          if (!jaxrsMethodsNoVarDecl.isEmpty()) {
            // such a pretty printing ...
            if (jaxrsMethodsNoVarDecl.size() == 1) {
              System.out.println("WARN: For the following method, the response body type could be determined " +
                      "from the source file, namely from the 'responseBody' variable declared in the method body, " +
                      "but actually no such variable declration found (try to declare the 'responseBody' variable):");
              System.out.println("  " + jaxrsMethodsNoVarDecl.get(0).asString());
            } else {
              System.out.println("WARN: For the following methods, the response body type could be determined " +
                      "from the source file, namely from the 'responseBody' variable declared in the method body, " +
                      "but actually no such variable declration found (try to declare the 'responseBody' variable):");
              for (JaxrsMethodWithLocation jm: jaxrsMethodsNoVarDecl) {
                System.out.println("  " + jm.asString());
              }
            }
          }
        }


        // match methods
        MethodMatcher matcher = new MethodMatcherImpl();
        for (MethodMapping mm: methodMappings) {
          if (!matcher.match(mm.apiSpecMethod.method, mm.jaxrsMethod.method)) {
            System.out.println("FAIL: Method match failed:");
            System.out.println("  " + mm.jaxrsMethod.asString());
            System.out.println("  <=> " + mm.apiSpecMethod.asString());

            success = false;
          }
        }

        if (success) {
          System.out.println("Match succeeded");
        }

      } catch (Throwable e) { throw new RuntimeException(e); }
    }
  }
}
