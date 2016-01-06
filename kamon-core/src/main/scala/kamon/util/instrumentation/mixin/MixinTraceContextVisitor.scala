package kamon.util.instrumentation.mixin

import net.bytebuddy.jar.asm.commons.AdviceAdapter
import net.bytebuddy.jar.asm.{ Opcodes, MethodVisitor, ClassVisitor }

class MixinTraceContextVisitor(mixin: MixinDescription, classVisitor: ClassVisitor) extends MixinClassVisitor(mixin, classVisitor) {

  override def visitMethod(access: Int, name: String, desc: String, signature: String, exceptions: Array[String]): MethodVisitor = {
    val mv = super.visitMethod(access, name, desc, signature, exceptions)
    if (!name.startsWith(ConstructorDescriptor)) return mv
    if ((Opcodes.ACC_PUBLIC & access) == 0) return mv // skipping non public methods
    TraceContextInitializer(this.className.getInternalName, mv, access, name, desc)
  }

  class TraceContextInitializer private (className: String, mv: MethodVisitor, access: Int, name: String, desc: String) extends AdviceAdapter(Opcodes.ASM5, mv, access, name, desc) {
    override def onMethodExit(opcode: Int): Unit = {
      if (opcode != Opcodes.ATHROW) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETSTATIC, "kamon/trace/TraceContextAware$", "MODULE$", "Lkamon/trace/TraceContextAware$;")
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "kamon/trace/TraceContextAware$", "default", "()Lkamon/trace/TraceContextAware;", false)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "kamon/trace/TraceContextAware", "traceContext", "()Lkamon/trace/TraceContext;", true)
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, "traceContext", "Lkamon/trace/TraceContext;")
      }
    }
  }

  object TraceContextInitializer {
    def apply(internalName: String, mv: MethodVisitor, access: Int, name: String, desc: String): TraceContextInitializer = new TraceContextInitializer(internalName, mv, access, name, desc)
  }
}
