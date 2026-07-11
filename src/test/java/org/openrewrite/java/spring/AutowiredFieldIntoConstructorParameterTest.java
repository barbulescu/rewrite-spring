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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;
import static org.openrewrite.kotlin.Assertions.kotlin;

class AutowiredFieldIntoConstructorParameterTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AutowiredFieldIntoConstructorParameter())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "spring-beans-5.+", "junit-jupiter-api"))
          .parser(KotlinParser.builder()
            .classpathFromResources(new InMemoryExecutionContext(), "spring-beans-5"));
    }

    @DocumentExample
    @Test
    void fieldIntoNewConstructor() {
        //language=java
        rewriteRun(
          java(
            """
              package demo;

              import org.springframework.beans.factory.annotation.Autowired;

              public class A {

                  @Autowired
                  private String a;

              }
              """,
            """
              package demo;

              public class A {

                  private final String a;

                  A(String a) {
                      this.a = a;
                  }

              }
              """
          )
        );
    }

    @Nested
    class Positive {

        @Test
        void fieldIntoExistingEmptyConstructor() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      A() {
                      }

                  }
                  """,
                """
                  package demo;

                  public class A {

                      private final String a;

                      A(String a) {
                          this.a = a;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void fieldIntoExistingConstructorWithUnrelatedParameter() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      private long l;

                      A(long l) {
                          this.l = l;
                      }

                  }
                  """,
                """
                  package demo;

                  public class A {

                      private final String a;

                      private long l;

                      A(long l, String a) {
                          this.l = l;
                          this.a = a;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void constructorPlacedBeforeFirstMethod() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      void run() {
                      }

                  }
                  """,
                """
                  package demo;

                  public class A {

                      private final String a;

                      A(String a) {
                          this.a = a;
                      }

                      void run() {
                      }

                  }
                  """
              )
            );
        }

        @Test
        void genericFieldType() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import java.util.List;
                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private List<String> a;

                  }
                  """,
                """
                  package demo;

                  import java.util.List;

                  public class A {

                      private final List<String> a;

                      A(List<String> a) {
                          this.a = a;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void arrayFieldType() {
            //language=java
            rewriteRun(
              // JavaTemplate mis-attributes the generated assignment for array-typed fields (the right-hand side
              // identifier lacks a type); an upstream JavaTemplate limitation, not specific to this recipe.
              spec -> spec.afterTypeValidationOptions(TypeValidation.all().identifiers(false)),
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String[] a;

                  }
                  """,
                """
                  package demo;

                  public class A {

                      private final String[] a;

                      A(String[] a) {
                          this.a = a;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void outerClassConvertedNestedClassLeftUntouched() {
            //language=java
            rewriteRun(
              // The delegated visitor does not descend into nested classes, so only the outer class is converted;
              // the `Autowired` import is retained because the nested class still uses it.
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      public static class B {

                          @Autowired
                          private String b;

                      }

                  }
                  """,
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      private final String a;

                      public static class B {

                          @Autowired
                          private String b;

                      }

                      A(String a) {
                          this.a = a;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void unrelatedInstantiationDoesNotBlockConversion() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  public class C {
                  }
                  """
              ),
              java(
                """
                  package demo;

                  public class Factory {
                      C create() {
                          return new C();
                      }
                  }
                  """
              ),
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                  }
                  """,
                """
                  package demo;

                  public class A {

                      private final String a;

                      A(String a) {
                          this.a = a;
                      }

                  }
                  """
              )
            );
        }
    }

    @Nested
    class SkipsIneligibleFields {

        @Test
        void requiredFalse() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired(required = false)
                      private String a;

                  }
                  """
              )
            );
        }

        @Test
        void staticField() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private static String a;

                  }
                  """
              )
            );
        }

        @Test
        void fieldWithInitializer() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a = "default";

                  }
                  """
              )
            );
        }

        @Test
        void fieldWithQualifier() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;
                  import org.springframework.beans.factory.annotation.Qualifier;

                  public class A {

                      @Autowired
                      @Qualifier("b")
                      private String a;

                  }
                  """
              )
            );
        }

        @Test
        void twoAutowiredFields() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      @Autowired
                      private String b;

                  }
                  """
              )
            );
        }

        @Test
        void multiVariableDeclaration() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a, b;

                  }
                  """
              )
            );
        }
    }

    @Nested
    class SkipsReassignedFields {

        @Test
        void fieldReassignedInMethod() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      void reset() {
                          this.a = null;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void fieldReassignedInLambda() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      Runnable reset() {
                          return () -> a = null;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void fieldIncremented() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private Integer a;

                      void bump() {
                          a++;
                      }

                  }
                  """
              )
            );
        }

        @Test
        void fieldAssignedInConstructor() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      A() {
                          this.a = "something";
                      }

                  }
                  """
              )
            );
        }
    }

    @Nested
    class SkipsIneligibleClasses {

        @Test
        void twoConstructorsWithoutAutowired() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      A() {
                      }

                      A(long l) {
                      }

                  }
                  """
              )
            );
        }

        @Test
        void constructorParameterNameCollision() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                      A(long a) {
                      }

                  }
                  """
              )
            );
        }

        @Test
        void abstractClass() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public abstract class A {

                      @Autowired
                      private String a;

                  }
                  """
              )
            );
        }

        @Test
        void enumClass() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public enum A {
                      INSTANCE;

                      @Autowired
                      private String a;

                  }
                  """
              )
            );
        }

        @Test
        void lombokConstructorAnnotation() {
            //language=java
            rewriteRun(
              java(
                """
                  package lombok;

                  import java.lang.annotation.ElementType;
                  import java.lang.annotation.Retention;
                  import java.lang.annotation.RetentionPolicy;
                  import java.lang.annotation.Target;

                  @Target(ElementType.TYPE)
                  @Retention(RetentionPolicy.SOURCE)
                  public @interface RequiredArgsConstructor {
                  }
                  """
              ),
              java(
                """
                  package demo;

                  import lombok.RequiredArgsConstructor;
                  import org.springframework.beans.factory.annotation.Autowired;

                  @RequiredArgsConstructor
                  public class A {

                      @Autowired
                      private String a;

                  }
                  """
              )
            );
        }

        @Test
        void junitTestClass() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.junit.jupiter.api.Test;
                  import org.springframework.beans.factory.annotation.Autowired;

                  public class ATest {

                      @Autowired
                      private String a;

                      @Test
                      void run() {
                      }

                  }
                  """
              )
            );
        }
    }

    @Nested
    class SkipsClassesUsedElsewhere {

        @Test
        void instantiatedFromMainSource() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                  }
                  """
              ),
              java(
                """
                  package demo;

                  public class Factory {
                      A create() {
                          return new A();
                      }
                  }
                  """
              )
            );
        }

        @Test
        void instantiatedFromTestSource() {
            //language=java
            rewriteRun(
              srcMainJava(
                java(
                  """
                    package demo;

                    import org.springframework.beans.factory.annotation.Autowired;

                    public class A {

                        @Autowired
                        private String a;

                    }
                    """
                )
              ),
              srcTestJava(
                java(
                  """
                    package demo;

                    public class ATest {
                        A subject = new A();
                    }
                    """
                )
              )
            );
        }

        @Test
        void subclassed() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                  }
                  """
              ),
              java(
                """
                  package demo;

                  public class B extends A {
                  }
                  """
              )
            );
        }

        @Test
        void anonymousSubclass() {
            //language=java
            rewriteRun(
              java(
                """
                  package demo;

                  import org.springframework.beans.factory.annotation.Autowired;

                  public class A {

                      @Autowired
                      private String a;

                  }
                  """
              ),
              java(
                """
                  package demo;

                  public class Factory {
                      A create() {
                          return new A() {
                          };
                      }
                  }
                  """
              )
            );
        }
    }

    @Nested
    class Kotlin {

        @Test
        void kotlinSourceUntouched() {
            //language=kotlin
            rewriteRun(
              kotlin(
                """
                  import org.springframework.beans.factory.annotation.Autowired

                  class A {
                      @Autowired
                      lateinit var a: String
                  }
                  """
              )
            );
        }
    }
}
