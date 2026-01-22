package org.checkerframework.maven.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "check",
        defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true)
public class CheckerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor pluginDescriptor;

    @Parameter(property = "checkers")
    private List<String> checkers;

    @Parameter(property = "checkerFrameworkVersion", defaultValue = "3.21.3")
    private String checkerFrameworkVersion;

    @Parameter(property = "extraJavacArgs")
    private List<String> extraJavacArgs;

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Starting Checker Framework analysis...");

        if (checkers == null || checkers.isEmpty()) {
            getLog().warn("No checkers configured. Skipping analysis.");
            return;
        }

        List<File> sources = getProjectSources();
        if (sources.isEmpty()) {
            getLog().info("No source files to analyze.");
            return;
        }

        String classpath = buildClasspath();
        String processorPath = buildProcessorPath();
        List<String> javacArgs = buildJavacArguments(classpath, sources.stream().map(File::getAbsolutePath).collect(Collectors.toList()));

        getLog().info("Running Checker Framework with the following options:");
        for (String arg : javacArgs) {
            getLog().info("  " + arg);
        }

        try {
            List<URL> processorPathUrls = new ArrayList<>();
            for (String path : processorPath.split(File.pathSeparator)) {
                processorPathUrls.add(new File(path).toURI().toURL());
            }

            URLClassLoader classLoader = new URLClassLoader(processorPathUrls.toArray(new URL[0]));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new MojoExecutionException("Could not get system Java compiler. Make sure you are running on a JDK, not a JRE.");
            }

            StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null, javacArgs, null, fileManager.getJavaFileObjectsFromFiles(sources));

            Object processor = classLoader.loadClass("org.checkerframework.javacutil.ParallelGentleMessageChecker").getDeclaredConstructor().newInstance();
            task.setProcessors(Collections.singletonList((javax.annotation.processing.Processor) processor));

            if (!task.call()) {
                throw new MojoExecutionException("Checker Framework analysis found errors.");
            } else {
                getLog().info("Checker Framework analysis finished successfully.");
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error running Checker Framework", e);
        }
    }

    private List<File> getProjectSources() {
        List<String> compileSourceRoots = project.getCompileSourceRoots();
        return compileSourceRoots.stream()
                .map(File::new)
                .flatMap(this::findJavaFiles)
                .collect(Collectors.toList());
    }

    private Stream<File> findJavaFiles(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return Stream.empty();
        }

        List<File> javaFiles = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    javaFiles.addAll(findJavaFiles(file).collect(Collectors.toList()));
                } else if (file.getName().endsWith(".java")) {
                    javaFiles.add(file);
                }
            }
        }
        return javaFiles.stream();
    }


    private String buildClasspath() throws MojoExecutionException {
        Set<Artifact> artifacts = project.getArtifacts();
        List<String> classpathElements = new ArrayList<>();

        for (Artifact artifact : artifacts) {
            File file = artifact.getFile();
            if (file != null) {
                classpathElements.add(file.getAbsolutePath());
            }
        }

        // Add the project's output directory to the classpath
        classpathElements.add(project.getBuild().getOutputDirectory());

        return String.join(File.pathSeparator, classpathElements);
    }

    private String buildProcessorPath() {
        List<String> classpathElements = new ArrayList<>();
        for (Artifact artifact : pluginDescriptor.getArtifacts()) {
            File file = artifact.getFile();
            if (file != null) {
                classpathElements.add(file.getAbsolutePath());
            }
        }
        return String.join(File.pathSeparator, classpathElements);
    }

    private List<String> buildJavacArguments(String classpath, List<String> sources) {
        List<String> javacArgs = new ArrayList<>();

        // Add classpath
        javacArgs.add("-cp");
        javacArgs.add(classpath);

        // Add output directory
        javacArgs.add("-d");
        javacArgs.add(outputDirectory.getAbsolutePath());

        // Add checkers
        javacArgs.add("-Acheckers=" + String.join(",", checkers));

        if (extraJavacArgs != null) {
            javacArgs.addAll(extraJavacArgs);
        }
        
        // Add source files to the end of the list
        javacArgs.addAll(sources);
        
        return javacArgs;
    }
}
