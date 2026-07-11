/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.spring;

import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.FindAnnotations;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.stream.Collectors.toList;

public class AutowiredFieldIntoConstructorParameter extends ScanningRecipe<Set<String>> {
    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final AnnotationMatcher AUTOWIRED_REQUIRED_MATCHER = new AnnotationMatcher("@" + AUTOWIRED + "(true)");

    @Getter
    final String displayName = "Convert `@Autowired` field injection into constructor injection";

    @Getter
    final String description = "Converts a field annotated with `@Autowired` into a constructor parameter assigned to a `final` field, " +
            "which is the injection style recommended by the Spring team. " +
            "The conversion is deliberately conservative so that the project still compiles and its tests stay green afterwards: " +
            "classes are skipped when they are instantiated with `new`, subclassed, abstract, test classes, use Lombok constructor annotations, " +
            "have ambiguous constructors, or when the field is written to outside the constructor or carries any other annotation. " +
            "Only top-level classes with a single `@Autowired` field are converted for now. " +
            "Note that only sources included in the same run are inspected for usages; consumers outside the repository are not visible.";

    @Override
    public Set<String> getInitialValue(ExecutionContext ctx) {
        return ConcurrentHashMap.newKeySet();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> unsafeClasses) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                // Any explicit instantiation would break when the constructor signature changes
                markUnsafe(newClass.getClazz() == null ? newClass.getType() : newClass.getClazz().getType());
                return super.visitNewClass(newClass, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                // Subclasses rely on the implicit `super()` which breaks when the superclass gains a constructor
                if (classDecl.getExtends() != null) {
                    markUnsafe(classDecl.getExtends().getType());
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }

            private void markUnsafe(@Nullable JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                if (fq != null) {
                    unsafeClasses.add(fq.getFullyQualifiedName());
                }
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Set<String> unsafeClasses) {
        return Preconditions.check(new UsesType<>(AUTOWIRED, false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile && !(tree instanceof J.CompilationUnit)) {
                    // Kotlin and Groovy sources are not converted yet
                    return (J) tree;
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J.CompilationUnit result = cu;
                for (FieldConversion conversion : findConversions(cu, unsafeClasses)) {
                    result = (J.CompilationUnit) new AutowiredFieldIntoConstructorParameterVisitor(
                            conversion.getClassFqn(), conversion.getFieldName()).visitNonNull(result, ctx);
                }
                return result;
            }
        });
    }

    @Value
    private static class FieldConversion {
        String classFqn;
        String fieldName;
    }

    private static List<FieldConversion> findConversions(J.CompilationUnit cu, Set<String> unsafeClasses) {
        // The delegated visitor does not descend into classes with a non-matching name, so nested classes cannot be converted
        return cu.getClasses().stream()
                .filter(classDecl -> classDecl.getType() != null)
                .map(classDecl -> findEligibleFieldName(classDecl, unsafeClasses)
                        .map(fieldName -> new FieldConversion(classDecl.getType().getFullyQualifiedName(), fieldName)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    private static Optional<String> findEligibleFieldName(J.ClassDeclaration classDecl, Set<String> unsafeClasses) {
        if (!isEligibleClass(classDecl, unsafeClasses)) {
            return Optional.empty();
        }
        List<J.VariableDeclarations> autowiredFields = autowiredFields(classDecl);
        if (autowiredFields.size() != 1 || !isEligibleField(autowiredFields.get(0))) {
            return Optional.empty();
        }
        J.VariableDeclarations.NamedVariable variable = autowiredFields.get(0).getVariables().get(0);
        String fieldName = variable.getSimpleName();
        if (isFieldWritten(classDecl, variable.getVariableType(), fieldName) ||
                collidesWithConstructorParameter(classDecl, fieldName)) {
            return Optional.empty();
        }
        return Optional.of(fieldName);
    }

    private static boolean isEligibleClass(J.ClassDeclaration classDecl, Set<String> unsafeClasses) {
        return classDecl.getKind() == J.ClassDeclaration.Kind.Type.Class &&
                classDecl.getType() != null &&
                !classDecl.hasModifier(J.Modifier.Type.Abstract) &&
                !unsafeClasses.contains(classDecl.getType().getFullyQualifiedName()) &&
                // Lombok may generate constructors this recipe cannot see
                FindAnnotations.find(classDecl, "@lombok.*Constructor").isEmpty() &&
                !isTestClass(classDecl);
    }

    private static List<J.VariableDeclarations> autowiredFields(J.ClassDeclaration classDecl) {
        return classDecl.getBody().getStatements().stream()
                .filter(J.VariableDeclarations.class::isInstance)
                .map(J.VariableDeclarations.class::cast)
                .filter(field -> field.getLeadingAnnotations().stream()
                        .anyMatch(annotation -> TypeUtils.isOfClassType(annotation.getType(), AUTOWIRED)))
                .collect(toList());
    }

    private static boolean isEligibleField(J.VariableDeclarations field) {
        return field.getVariables().size() == 1 &&
                field.getVariables().get(0).getInitializer() == null &&
                !field.hasModifier(J.Modifier.Type.Static) &&
                !(field.getType() instanceof JavaType.Primitive) &&
                // Any other annotation (@Qualifier, @Value, ...) is not converted yet
                field.getLeadingAnnotations().size() == 1 &&
                AUTOWIRED_REQUIRED_MATCHER.matches(field.getLeadingAnnotations().get(0));
    }

    /**
     * A field written to anywhere in the class cannot become {@code final}.
     * Writes inside constructors also disqualify the field, matching the delegated visitor's own check.
     */
    private static boolean isFieldWritten(J.ClassDeclaration classDecl, JavaType.@Nullable Variable fieldType, String fieldName) {
        AtomicBoolean written = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, AtomicBoolean found) {
                return flagFieldReference(assignment.getVariable(), super.visitAssignment(assignment, found), found);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation assignOp, AtomicBoolean found) {
                return flagFieldReference(assignOp.getVariable(), super.visitAssignmentOperation(assignOp, found), found);
            }

            @Override
            public J.Unary visitUnary(J.Unary unary, AtomicBoolean found) {
                if (unary.getOperator().isModifying()) {
                    return flagFieldReference(unary.getExpression(), super.visitUnary(unary, found), found);
                }
                return super.visitUnary(unary, found);
            }

            private <T extends J> T flagFieldReference(Expression target, T tree, AtomicBoolean found) {
                if (isFieldReference(target, fieldType, fieldName)) {
                    found.set(true);
                }
                return tree;
            }
        }.visit(classDecl.getBody(), written);
        return written.get();
    }

    private static boolean isFieldReference(Expression expression, JavaType.@Nullable Variable fieldType, String fieldName) {
        if (expression instanceof J.Identifier) {
            J.Identifier identifier = (J.Identifier) expression;
            if (!fieldName.equals(identifier.getSimpleName())) {
                return false;
            }
            // Without type attribution stay conservative and treat a name match as a write to the field
            return fieldType == null || identifier.getFieldType() == null || fieldType.equals(identifier.getFieldType());
        }
        if (expression instanceof J.FieldAccess) {
            J.FieldAccess fieldAccess = (J.FieldAccess) expression;
            return fieldName.equals(fieldAccess.getSimpleName()) &&
                    fieldAccess.getTarget() instanceof J.Identifier &&
                    "this".equals(((J.Identifier) fieldAccess.getTarget()).getSimpleName());
        }
        return false;
    }

    private static boolean collidesWithConstructorParameter(J.ClassDeclaration classDecl, String fieldName) {
        J.MethodDeclaration constructor = applicableConstructor(classDecl);
        return constructor != null && constructor.getParameters().stream()
                .filter(J.VariableDeclarations.class::isInstance)
                .map(J.VariableDeclarations.class::cast)
                .flatMap(parameter -> parameter.getVariables().stream())
                .anyMatch(parameter -> fieldName.equals(parameter.getSimpleName()));
    }

    /**
     * Mirrors the constructor selection of {@link AutowiredFieldIntoConstructorParameterVisitor}:
     * the single constructor, or the single {@code @Autowired} constructor among several.
     */
    private static J.@Nullable MethodDeclaration applicableConstructor(J.ClassDeclaration classDecl) {
        List<J.MethodDeclaration> constructors = classDecl.getBody().getStatements().stream()
                .filter(J.MethodDeclaration.class::isInstance)
                .map(J.MethodDeclaration.class::cast)
                .filter(J.MethodDeclaration::isConstructor)
                .collect(toList());
        if (constructors.size() == 1) {
            return constructors.get(0);
        }
        List<J.MethodDeclaration> autowired = constructors.stream()
                .filter(constructor -> constructor.getLeadingAnnotations().stream()
                        .anyMatch(annotation -> TypeUtils.isOfClassType(annotation.getType(), AUTOWIRED)))
                .collect(toList());
        return autowired.size() == 1 ? autowired.get(0) : null;
    }

    private static boolean isTestClass(J.ClassDeclaration classDecl) {
        AtomicBoolean found = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, AtomicBoolean testAnnotationFound) {
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                if (type != null && isTestAnnotation(type.getFullyQualifiedName())) {
                    testAnnotationFound.set(true);
                }
                return super.visitAnnotation(annotation, testAnnotationFound);
            }
        }.visit(classDecl, found);
        return found.get();
    }

    private static boolean isTestAnnotation(String fqn) {
        // Test classes need `@TestConstructor` for constructor injection, which this recipe does not add yet
        return fqn.startsWith("org.junit.") ||
                fqn.startsWith("org.testng.") ||
                fqn.startsWith("org.springframework.test.") ||
                fqn.startsWith("org.springframework.boot.test.");
    }
}
