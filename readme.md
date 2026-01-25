# Maven Checker Framework Plugin

[![License](https://img.shields.io/badge/license-apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

This plugin configures Maven to use the [Checker Framework](https://checkerframework.org) for pluggable type-checking during the compilation phase.

## Download

Add the following to your `pom.xml` file:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.checkerframework</groupId>
      <artifactId>checkerframework-maven-plugin</artifactId>
      <version>1.0-SNAPSHOT</version>
      <configuration>
        <annotationProcessors>
          <annotationProcessor>org.checkerframework.checker.nullness.NullnessChecker</annotationProcessor>
        </annotationProcessors>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>check</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

The plugin runs during the `process-classes` phase by default, which occurs after compilation but before packaging.

## Configuration

### Configuring which checkers to use

The `annotationProcessors` property lists which checkers will be run.

For example:

```xml
<plugin>
  <groupId>org.checkerframework</groupId>
  <artifactId>checkerframework-maven-plugin</artifactId>
  <version>1.0-SNAPSHOT</version>
  <configuration>
    <annotationProcessors>
      <annotationProcessor>org.checkerframework.checker.nullness.NullnessChecker</annotationProcessor>      
    </annotationProcessors>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

For a list of checkers, see the [Checker Framework Manual](https://checkerframework.org/manual/#introduction).

### Providing checker-specific options to the compiler

You can set the `extraJavacArgs` property in order to pass additional options to the compiler when running a typechecker.

For example, to use a stub file:

```xml
<configuration>
  <annotationProcessors>
    <annotationProcessor>org.checkerframework.checker.nullness.NullnessChecker</annotationProcessor>
  </annotationProcessors>
  <extraJavacArgs>
    <arg>-Werror</arg>
    <arg>-Astubs=/path/to/my/stub/file.astub</arg>
  </extraJavacArgs>
</configuration>
```

### Specifying a Checker Framework version

The plugin determines which version of the Checker Framework to use in the following order of priority:

1. **Explicitly configured version**: If you set the `checkerFrameworkVersion` property in the plugin configuration, that version will be used.

2. **Version from project dependencies**: If not explicitly configured, the plugin will automatically detect the Checker Framework version from your project dependencies by looking for the `checker-qual` artifact.

3. **Default version**: If no version is found in dependencies, the plugin uses the default version 3.53.0.

You can explicitly specify a Checker Framework version by setting the `checkerFrameworkVersion` property:

```xml
<configuration>
  <checkerFrameworkVersion>3.52.0</checkerFrameworkVersion>
  <annotationProcessors>
    <annotationProcessor>org.checkerframework.checker.nullness.NullnessChecker</annotationProcessor>
  </annotationProcessors>
</configuration>
```

Alternatively, you can add the Checker Framework as a dependency in your project, and the plugin will automatically use that version:

```xml
<dependencies>
  <dependency>
    <groupId>org.checkerframework</groupId>
    <artifactId>checker-qual</artifactId>
    <version>3.52.0</version>
  </dependency>
</dependencies>
```

The plugin will automatically resolve and download the `checker` and `checker-qual` JARs from your project dependencies, local Maven repository, or remote repositories as needed.

### Other options

* You can disable the Checker Framework temporarily (e.g. when testing something unrelated) either in your `pom.xml` or from the command line. In your `pom.xml`:

  ```xml
  <configuration>
    <skip>true</skip>
  </configuration>
  ```

* By default, the plugin applies the selected checkers to all source roots, including test sources such as `src/test/java`.

  Here is how to prevent checkers from being applied to test sources:

  ```xml
  <configuration>
    <excludeTests>true</excludeTests>
    <annotationProcessors>
      <annotationProcessor>org.checkerframework.checker.nullness.NullnessChecker</annotationProcessor>
    </annotationProcessors>
  </configuration>
  ```

* By default, the plugin only processes annotations and does not generate class files (to avoid overwriting compilation results). This is controlled by the `procOnly` parameter, which defaults to `true`. If you want to generate class files, set it to `false`:

  ```xml
  <configuration>
    <procOnly>false</procOnly>
  </configuration>
  ```

* By default, the plugin fails the build when Checker Framework finds errors. This is controlled by the `failOnError` parameter, which defaults to `true`. If you want to continue the build even when errors are found, set it to `false`:

  ```xml
  <configuration>
    <failOnError>false</failOnError>
  </configuration>
  ```

* The plugin automatically skips execution for projects with packaging type `pom`.

## JDK 8 vs JDK 9+ implementation details

The plugin attempts to automatically configure the Checker Framework on both Java 8 and Java 9+ JVMs, following the [best practices in the Checker Framework manual](https://checkerframework.org/manual/#javac). In particular:

* If both the JVM and target versions are 8, and the Checker Framework version is >= 3.0 or >= 2.11.0, the plugin automatically uses the Error Prone javac compiler.
* If the JVM version is 9+, the plugin automatically adds the `--add-exports` and `--add-opens` options to `javac`.

The plugin detects the Java source version from `maven.compiler.source` or `maven.compiler.target` properties, and the JVM version from the toolchain (if configured) or the system property.

## Toolchain support

The plugin supports Maven toolchains. If you have configured a JDK toolchain in your `pom.xml` or `~/.m2/toolchains.xml`, the plugin will use that toolchain to determine the Java version and locate the Java executable.

Example toolchain configuration:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-toolchains-plugin</artifactId>
      <version>3.1.0</version>
      <executions>
        <execution>
          <goals>
            <goal>toolchain</goal>
          </goals>
        </execution>
      </executions>
      <configuration>
        <toolchains>
          <jdk>
            <version>11</version>
          </jdk>
        </toolchains>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## Using a locally-built plugin

You can build the plugin locally rather than downloading it from Maven Central.

To build the plugin from source, run `mvn clean install`.

If you want to use a locally-built version of the plugin, you can install it to your local Maven repository by running `mvn clean install`. Then, use the plugin in your project as described in the Download section above.

## Complete example

Here is a complete example `pom.xml` showing how to use the plugin:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>my-project</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <maven.compiler.source>11</maven.compiler.source>
    <maven.compiler.target>11</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.checkerframework</groupId>
      <artifactId>checker-qual</artifactId>
      <version>3.53.0</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.checkerframework</groupId>
        <artifactId>checkerframework-maven-plugin</artifactId>
        <version>1.0-SNAPSHOT</version>
        <configuration>
          <annotationProcessors>
            <annotationProcessor>org.checkerframework.checker.nullness.NullnessChecker</annotationProcessor>            
          </annotationProcessors>
          <extraJavacArgs>
            <arg>-Werror</arg>
          </extraJavacArgs>
          <excludeTests>false</excludeTests>
          <failOnError>true</failOnError>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>check</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

## Credits

This project was inspired by and references the implementation of the [Gradle Checker Framework Plugin](https://github.com/kelloggm/checkerframework-gradle-plugin). It also draws from the code of the previously abandoned Maven plugin in the [Checker Framework repository](https://github.com/typetools/checker-framework/tree/checker-framework-1.8.2/maven-plugin).

## License

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   <http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
