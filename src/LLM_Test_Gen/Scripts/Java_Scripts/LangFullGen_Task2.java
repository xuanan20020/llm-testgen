package org.apache.commons.lang3;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.javadoc.Javadoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sootup.callgraph.CallGraph;
import sootup.callgraph.CallGraphAlgorithm;
import sootup.callgraph.RapidTypeAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.types.Type;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class LangFullGen {
    private static final Logger log = LoggerFactory.getLogger(LangFullGen.class);

    public static void main(String[] args) {
        extractMethodsInfo();
    }

    public static void extractMethodsInfo() {
        try {
            Path pathToBinary = Paths.get("target/classes");
            String sourceRoot = "src/main/java";

            AnalysisInputLocation inputLocation = PathBasedAnalysisInputLocation.create(pathToBinary, null);
            JavaView view = new JavaView(inputLocation);
            List<SootClass> allLangClasses = view.getClasses().collect(Collectors.toList());

            BufferedWriter writer = new BufferedWriter(new FileWriter("Test_Data.csv"));
            writer.write("FQN,Signature,Jimple,Callees,MethodModifiers,ClassModifiers" +
                    ",JavaDoc,MethodBody,Imports,classJavaDoc,classFnCs\n");

            for (SootClass cls : allLangClasses) {
                String fqnClassName = cls.getType().getFullyQualifiedName();
                String fqnClassNameSafe = fqnClassName.contains("$")
                        ? fqnClassName.substring(0, fqnClassName.indexOf('$'))
                        : fqnClassName;

                String javaPath = sourceRoot + "/" + fqnClassNameSafe.replace('.', '/') + ".java";
                Path sourceFile = Paths.get(javaPath);
                CompilationUnit cu = StaticJavaParser.parse(sourceFile);

                for (SootMethod method : cls.getMethods()) {
                    try {
                        // ------------ From JavaParser ------------
                        String methodModifiers = "";
                        String classModifiers = "";
                        String javaDoc = "";
                        String methodBody = "";
                        String imports = "";
                        String classJavaDoc = "";
                        String classFieldsAndConstructors = "";

                        if (cu != null) {
                            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                                if (md.getNameAsString().equals(method.getName()) &&
                                        parametersMatch(md.getParameters(), method.getParameterTypes())) {

                                    methodModifiers = md.getModifiers().toString();

                                    Optional<ClassOrInterfaceDeclaration> classDecl = md.findAncestor(ClassOrInterfaceDeclaration.class);
                                    classModifiers = classDecl.map(c -> c.getModifiers().toString()).orElse("");

                                    classJavaDoc = classDecl.flatMap(NodeWithJavadoc::getJavadoc).map(Javadoc::toText).orElse("");

                                    if (classDecl.isPresent()) {
                                        classFieldsAndConstructors = getFieldsAndConstructors(classDecl.get());
                                    }

                                    javaDoc = md.getJavadoc().map(Javadoc::toText).orElse("");
                                    methodBody = md.getBody().map(Object::toString).orElse("");
                                    imports = cu.getImports().toString();
                                    break;
                                }
                            }
                        }

                        // ----------- From SootUp --------------
                        String fqn = method.getDeclClassType().getFullyQualifiedName() + "." +
                                method.getName() + "(" +
                                method.getParameterTypes().stream()
                                      .map(Object::toString)
                                      .collect(Collectors.joining(", ")) + ")";
                        String signature = method.getSignature().getSubSignature().toString();
                        String jimpleCode = "";
                        if (!methodModifiers.contains("abstract")) {
                            jimpleCode = method.getBody().toString();
                        }

                        // ----------- Call Graph Extraction -----------
                        String calleesStr;
                        try {
                            // -- RTA call graph for this entry method only --
                            CallGraphAlgorithm cha = new RapidTypeAnalysisAlgorithm(view);
                            CallGraph callGraph = cha.initialize(Collections.singletonList(method.getSignature()));
                            // List all callees of this method, flatten to simple string
                            List<String> callees = callGraph.callsFrom(method.getSignature()).stream()
                                                            .map(String::valueOf)
                                                            .collect(Collectors.toList());
                            calleesStr = String.join("\\n", callees);
                        } catch (Exception cgErr) {
                            calleesStr = "";
                        }

                        // CSV-safe replacements
                        jimpleCode = sanitizeForCsv(jimpleCode);
                        calleesStr = sanitizeForCsv(calleesStr);

                        methodModifiers = sanitizeForCsv(methodModifiers);
                        classModifiers = sanitizeForCsv(classModifiers);
                        classJavaDoc = sanitizeForCsv(classJavaDoc);
                        classFieldsAndConstructors = sanitizeForCsv(classFieldsAndConstructors);
                        javaDoc = sanitizeForCsv(javaDoc);
                        methodBody = sanitizeForCsv(methodBody);
                        imports = sanitizeForCsv(imports);

                        writer.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"" +
                                        ",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                                fqn, signature, jimpleCode, calleesStr, methodModifiers,
                                classModifiers, javaDoc, methodBody, imports, classJavaDoc, classFieldsAndConstructors));

                    } catch (Exception e) {
                        System.out.println("Skipping method due to error: " + e.getMessage());
                    }
                }
            }
            writer.close();
            System.out.println("Extraction complete. Data saved to Test_Data.csv.");

        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    private static String sanitizeForCsv(String input) {
        return input.replace("\n", "\\n").replace(",", ";");
    }

    private static String getFieldsAndConstructors(ClassOrInterfaceDeclaration cls) {
        StringBuilder sb = new StringBuilder();
        for (FieldDeclaration fd : cls.getFields()) {
            sb.append(fd.toString()).append("\n");
        }

        if (sb.length() != 0) {
            sb.append("\n");
        }

        for (ConstructorDeclaration cd : cls.getConstructors()) {
            sb.append(cd.toString()).append("\n");
        }
        return sb.toString();
    }

    private static boolean parametersMatch(NodeList<Parameter> mdParams, List<Type> sootTypes) {
        if (mdParams.size() != sootTypes.size()) {
            return false;
        }

        for (int i = 0; i < mdParams.size(); i++) {
            // Get JavaParser parameter as lowercase string
            String jpParam = mdParams.get(i).getType().toString().toLowerCase();

            // Get the last part of SootUp type (after dot), keep only letters, lowercase
            String sootParamFull = sootTypes.get(i).toString();
            String[] parts = sootParamFull.split("\\.");
            String lastPart = parts[parts.length - 1].replaceAll("[^A-Za-z]", "").toLowerCase();

            if (!jpParam.contains(lastPart)) {
                return false;
            }
        }
        return true;
    }

}
