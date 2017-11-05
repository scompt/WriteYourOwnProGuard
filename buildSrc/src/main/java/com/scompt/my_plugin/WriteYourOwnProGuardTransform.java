package com.scompt.my_plugin;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.jgrapht.Graph;
import org.jgrapht.ext.DOTExporter;
import org.jgrapht.ext.ExportException;
import org.jgrapht.ext.IntegerComponentNameProvider;
import org.jgrapht.ext.StringComponentNameProvider;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.BreadthFirstIterator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.bytecode.ConstPool;

import static com.android.build.api.transform.Format.DIRECTORY;
import static com.android.build.api.transform.Format.JAR;

@SuppressWarnings("unused")
public class WriteYourOwnProGuardTransform extends Transform {

    private static Logger logger;

    public WriteYourOwnProGuardTransform(Project project) {
        logger = project.getLogger();
    }

    @Override
    public String getName() {
        return "Scompt";
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        Set<QualifiedContent.ContentType> contentTypes = new HashSet<>();
        contentTypes.add(QualifiedContent.DefaultContentType.CLASSES);
        return contentTypes;
    }

    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        Set<QualifiedContent.Scope> scopes = new HashSet<>();
        scopes.add(QualifiedContent.Scope.PROJECT);
        scopes.add(QualifiedContent.Scope.SUB_PROJECTS);
        scopes.add(QualifiedContent.Scope.EXTERNAL_LIBRARIES);
        return scopes;
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        Path temporaryPath = transformInvocation.getContext().getTemporaryDir().toPath();
        logger.quiet("temp: " + temporaryPath);

        List<Path> classes = unpack(transformInvocation, temporaryPath);

        Multimap<String, String> dependencies = calculateDependencies(classes);
        Graph<String, DefaultEdge> graph = buildGraph(dependencies);

        try {
            printGraphViz(graph);
        } catch (ExportException e) {
            throw new TransformException(e);
        }

        ImmutableSortedSet<String> reachableNodes = findReachableNodes(graph,
                                                                       "com.scompt.myapplication.MyActivity");


        woot(transformInvocation, reachableNodes);
    }

    private List<Path> unpack(TransformInvocation transformInvocation, Path temporaryPath) throws IOException {
        LinkedList<Path> classPaths = new LinkedList<>();

        for (TransformInput input : transformInvocation.getInputs()) {
            logger.quiet("input: " + input);
            for (JarInput jarInput : input.getJarInputs()) {
                logger.quiet("jarInput: " + jarInput);

                try (JarInputStream is = new JarInputStream(Files.newInputStream(jarInput.getFile()
                                                                                         .toPath()))) {
                    JarEntry entry = is.getNextJarEntry();
                    while (entry != null) {
                        if (!entry.isDirectory()) {
                            String[] pathComponents = entry.getName()
                                                           .replaceFirst("/$",
                                                                         "")
                                                           .split("/");
                            logger.quiet("pathComponents: " + Arrays.toString(pathComponents));


                            if (pathComponents.length == 0) {
                                throw new IllegalStateException("Should not be empty");
                            }

                            Path path = temporaryPath;
                            if (pathComponents.length > 1) {
                                for (int i = 0; i < pathComponents.length - 1; i++) {
                                    path = path.resolve(pathComponents[i]);
                                    createDirectory(path);
                                }
                            }

                            path = path.resolve(pathComponents[pathComponents.length - 1]);
                            classPaths.add(path);

                            Files.copy(is, path);
                            logger.quiet("target: " + path);
                        }

                        entry = is.getNextJarEntry();
                    }
                }
            }

            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {

                logger.quiet("directoryInput: " + directoryInput);

                Path root = directoryInput.getFile()
                                          .toPath();
                Files.walkFileTree(root,
                                   new FileVisitor<Path>() {
                                       @Override
                                       public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                                           logger.quiet("dir: " + path);
                                           Path outputPath = temporaryPath
                                                   .resolve(root.relativize(path));
                                           createDirectory(outputPath);
                                           return FileVisitResult.CONTINUE;
                                       }

                                       @Override
                                       public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                                           logger.quiet("file: " + path);
                                           Path outputPath = temporaryPath
                                                   .resolve(root.relativize(path));

                                           Files.copy(path, outputPath);

                                           classPaths.add(outputPath);

                                           return FileVisitResult.CONTINUE;
                                       }

                                       @Override
                                       public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                                           return FileVisitResult.TERMINATE;
                                       }

                                       @Override
                                       public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                                           return FileVisitResult.CONTINUE;
                                       }
                                   });
            }
        }

        return classPaths;
    }

    private void woot(TransformInvocation transformInvocation, ImmutableSortedSet<String> reachableNodes) throws IOException {
        ClassPool classPool = ClassPool.getDefault();


        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        File flattenedDir = outputProvider.getContentLocation("blah",
                                                              getOutputTypes(),
                                                              getScopes(),
                                                              DIRECTORY);

        for (TransformInput input : transformInvocation.getInputs()) {
            logger.quiet("input: " + input);
            for (JarInput jarInput : input.getJarInputs()) {
                logger.quiet("jarInput: " + jarInput);

                File out2Dir = transformInvocation.getOutputProvider()
                                                  .getContentLocation(jarInput.getName(),
                                                                      getOutputTypes(), getScopes(),
                                                                      JAR);
                Files.copy(jarInput.getFile().toPath(), out2Dir.toPath());
            }

            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {

                logger.quiet("directoryInput: " + directoryInput);

                Path root = directoryInput.getFile()
                                          .toPath();

                File outDir = transformInvocation.getOutputProvider()
                                                 .getContentLocation("blogdonothing",
                                                                     getOutputTypes(), getScopes(),
                                                                     DIRECTORY);
                Path outputRoot = outDir.toPath();

                Files.walkFileTree(root,
                                   new FileVisitor<Path>() {
                                       @Override
                                       public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                                           logger.quiet("dir: " + path);
                                           Path outputPath = outputRoot
                                                   .resolve(root.relativize(path));
                                           createDirectory(outputPath);
                                           return FileVisitResult.CONTINUE;
                                       }

                                       @Override
                                       public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {

                                           try (FileInputStream classStream = new FileInputStream(
                                                   path.toFile())) {
                                               CtClass ctClass = classPool.makeClass(classStream);
                                               ctClass.makeUniqueName()
                                               String className = sanitizeClassName(
                                                       ctClass.getName());

                                               boolean isReachable = reachableNodes
                                                       .contains(className) || className.contains("R$");
                                               logger.quiet(
                                                       "file: " + path + " reachable: " + isReachable);

                                               if (isReachable) {
                                                   Path outputPath = outputRoot
                                                           .resolve(root.relativize(path));

                                                   Files.copy(path, outputPath);
                                               }
                                           }

                                           return FileVisitResult.CONTINUE;
                                       }

                                       @Override
                                       public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                                           return FileVisitResult.TERMINATE;
                                       }

                                       @Override
                                       public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                                           return FileVisitResult.CONTINUE;
                                       }
                                   });
            }
        }
    }

    private static ImmutableSortedSet<String> findReachableNodes(Graph<String, DefaultEdge> graph, String start) {
        BreadthFirstIterator<String, DefaultEdge> iterator = new BreadthFirstIterator<>(graph,
                                                                                        start);

        return ImmutableSortedSet.copyOf(iterator);
    }

    private static Graph<String, DefaultEdge> buildGraph(Multimap<String, String> dependencies) {
        Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        for (String s : dependencies.keySet()) {
            if (!s.startsWith("java.lang")) {
                graph.addVertex(s);
            }
        }
        for (String s : dependencies.values()) {
            if (!s.startsWith("java.lang")) {
                graph.addVertex(s);
            }
        }
        dependencies.forEach((sourceVertex, targetVertex) -> {
            if (sourceVertex.startsWith("java.lang")) return;
            if (targetVertex.startsWith("java.lang")) return;
            if (sourceVertex.equals(targetVertex)) return;

            graph.addEdge(sourceVertex,
                          targetVertex);
        });


        return graph;
    }

    private static void printGraphViz(Graph<String, DefaultEdge> graph) throws ExportException {
        DOTExporter<String, DefaultEdge> exporter = new DOTExporter<>(
                new IntegerComponentNameProvider<>(),
                new StringComponentNameProvider<>(),
                null);

        exporter.exportGraph(graph, System.out);
    }

    private static Multimap<String, String> calculateDependencies(List<Path> classPaths) {
        Multimap<String, String> dependencies = ArrayListMultimap.create(classPaths.size(),
                                                                         10);

        ClassPool classPool = ClassPool.getDefault();

        for (Path aClassPath : classPaths) {
            try (FileInputStream classStream = new FileInputStream(aClassPath.toFile())) {
                CtClass aClass = classPool.makeClassIfNew(classStream);
                String aClassName = aClass.getName();


                ConstPool constPool = aClass.getClassFile()
                                            .getConstPool();
                Set classNames = constPool.getClassNames();
                for (Object className : classNames) {
                    String st = (String) className;
                    dependencies.put(sanitizeClassName(aClassName),
                                     sanitizeClassName(st));
                }

            } catch (IOException e) {
//                logger.quiet("e: " + e);
            }
        }

        return dependencies;
    }

    private static String sanitizeClassName(String className) {
        return className.replaceAll("/",
                                    ".");
    }

    private static void createDirectory(Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.createDirectory(path);

        } else if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Existing path must be a directory: " + path);
        }
    }
}
