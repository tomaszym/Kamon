/*
 * =========================================================================================
 * Copyright © 2013-2015 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.util.instrumentation

import java.io.File
import java.lang.instrument.Instrumentation

import kamon.util.instrumentation.mixin.{ MixinTraceContextVisitor, MixinClassVisitor, MixinDescription }
import kamon.util.{ Mixin, MixinTraceContext }
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.AgentBuilder.Transformer
import net.bytebuddy.asm.ClassVisitorWrapper
import net.bytebuddy.description.`type`.TypeDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.dynamic.{ ClassFileLocator, DynamicType }
import net.bytebuddy.dynamic.DynamicType.Builder
import net.bytebuddy.jar.asm._
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatcher.Junction
import net.bytebuddy.matcher.ElementMatchers._
import net.bytebuddy.pool.TypePool

abstract class KamonInstrumentation {
  val typePool = TypePool.Default.ofClassPath()
  val NotDeclaredByObject: Junction[MethodDescription] = not(isDeclaredBy(classOf[Object]))
  val TakesArguments: Junction[MethodDescription] = not(takesArguments(0))
  val transformers = Seq.newBuilder[Transformer]
  var elementMatcher: ElementMatcher[_ >: TypeDescription] = _
  val mixins = Seq.newBuilder[MixinDescription]

  //  def mix(instrumentation: Instrumentation) = mixins.foreach {
  //    mixin ⇒
  //      new AgentBuilder.Default()
  //        .`type`(mixin.targetTypes, is(ClassLoader.getSystemClassLoader))
  //        .transform(withTransformer { (builder, typeDescription) ⇒
  //          builder.classVisitor(new ClassVisitorWrapper {
  //            override def mergeWriter(flags: Int): Int = flags
  //            override def mergeReader(flags: Int): Int = flags
  //            override def wrap(classVisitor: ClassVisitor): ClassVisitor = {
  //                          mixin.mixinType match {
  //                            case "traceContextMixin" => new MixinTraceContextVisitor(mixin, classVisitor)
  //                            case default => new MixinClassVisitor(mixin, classVisitor)
  //                           }
  //
  //                        }
  //          })
  //        })
  //        .installOn(instrumentation)

  //      new ByteBuddy()
  //        .rebase(typePool.describe(mixin.targets.head).resolve(), ClassFileLocator.ForClassLoader.ofClassPath())
  //        .classVisitor(new ClassVisitorWrapper {
  //          override def mergeWriter(flags: Int): Int = flags
  //          override def mergeReader(flags: Int): Int = flags | ClassReader.EXPAND_FRAMES
  //          override def wrap(classVisitor: ClassVisitor): ClassVisitor = {
  //            mixin.mixinType match {
  //              case "traceContextMixin" ⇒ new MixinTraceContextVisitor(mixin, classVisitor)
  //              case default             ⇒ new MixinClassVisitor(mixin, classVisitor)
  //            }
  //
  //          }
  //        })
  //        .make().load(ClassLoader.getSystemClassLoader(), ClassLoadingStrategy.Default.INJECTION);
  //        .saveIn(new File("/home/diego/traceContext11"));
  //      //       .installOn(instrumentation)
  //  }

  def register(instrumentation: Instrumentation): Unit = {
    //    mix(instrumentation)
    val builder = new AgentBuilder.Default()
      .withListener(new AgentBuilder.Listener() {
        override def onError(typeName: String, throwable: Throwable): Unit = {
          System.out.println("Error - " + typeName + ", " + throwable.getMessage());
        }

        override def onTransformation(typeDescription: TypeDescription, dynamicType: DynamicType): Unit = {
          System.out.println("Transformed - " + typeDescription + ", type = " + dynamicType);
        }

        override def onComplete(typeName: String): Unit = {
//          System.out.println("Completed - " + typeName);
        }

        override def onIgnored(typeDescription: TypeDescription): Unit = {
          //                          System.out.println("Ignored - " + typeDescription);
        }
      }).`type`(elementMatcher) //.transform(getTransformer()).installOn(instrumentation)
    mixins.result().foreach { mixin ⇒
      builder.transform(withTransformer { (b, _) ⇒
        b.classVisitor(new ClassVisitorWrapper {
          override def mergeWriter(flags: Int): Int = flags
          override def mergeReader(flags: Int): Int = flags | ClassReader.EXPAND_FRAMES
          override def wrap(classVisitor: ClassVisitor): ClassVisitor = {
            new MixinClassVisitor(mixin, classVisitor)
          }
        })
      }).installOn(instrumentation)
    }
    transformers.result.foreach(transformer ⇒ builder.transform(transformer).installOn(instrumentation))
  }

  def getTransformer(): Transformer = null

  def addTransformation(f: ⇒ (Builder[_], TypeDescription) ⇒ Builder[_]) = transformers += withTransformer(f)

  def withTransformer(f: ⇒ (Builder[_], TypeDescription) ⇒ Builder[_]) = new Transformer {
    override def transform(builder: Builder[_], typeDescription: TypeDescription): Builder[_] = {
      f.apply(builder, typeDescription)
    }
  }

  def getType: ElementMatcher[_ >: TypeDescription] = null
  def forTypes(f: ⇒ ElementMatcher[_ >: TypeDescription]): Unit = elementMatcher = f

  def addMixin(clazz: ⇒ Class[_]) = {
    mixins += MixinDescription(elementMatcher, clazz, "default")
  }
}

