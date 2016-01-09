/* =========================================================================================
 * Copyright © 2013-2016 the kamon project <http://kamon.io/>
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

package kamon.instrumentation.scala

import kamon.trace.{TraceContext, TraceContextAware, Tracer}
import kamon.util.initializer
import kamon.util.instrumentation.KamonInstrumentation
import net.bytebuddy.implementation.MethodDelegation._
import net.bytebuddy.implementation.bind.annotation.{RuntimeType, SuperCall, This}
import net.bytebuddy.matcher.ElementMatchers._

class FutureInstrumentation extends KamonInstrumentation {

  forTypes {
    named("scala.concurrent.impl.CallbackRunnable").or(
    named("scala.concurrent.impl.Future$PromiseCompletingRunnable"))
  }

  addMixin(classOf[InjectTraceContext])

  addTransformation ((builder, _) ⇒ builder.method(named("run")).intercept(to(FutureInterceptor)))

  object FutureInterceptor {
    @RuntimeType
    def run(@SuperCall r: Runnable, @This runnable: TraceContextAware): Unit = {
      Tracer.withContext(runnable.traceContext()) {
        r.run()
      }
    }
  }

  class InjectTraceContext extends TraceContextAware {
    @transient
    var traceContext: TraceContext =  _

    @initializer
    def init(): Unit =  this.traceContext = Tracer.currentContext
  }
}
