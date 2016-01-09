package kamon.instrumentation.play

import net.bytebuddy.asm.ClassVisitorWrapper
import net.bytebuddy.description.`type`.TypeDescription
import net.bytebuddy.jar.asm.commons.AdviceAdapter
import net.bytebuddy.jar.asm.{ ClassReader, ClassVisitor, MethodVisitor, Opcodes }

class RunnableVisitor private (typeDescription: TypeDescription) extends ClassVisitorWrapper {
  override def wrap(classVisitor: ClassVisitor): ClassVisitor = RunnableConstructorVisitor(classVisitor, typeDescription)
  override def mergeWriter(flags: Int): Int = flags
  override def mergeReader(flags: Int): Int = flags | ClassReader.EXPAND_FRAMES
}

object RunnableVisitor {
  def apply(typeDescription: TypeDescription): RunnableVisitor = new RunnableVisitor(typeDescription)
}

class RunnableConstructorVisitor private (cv: ClassVisitor, typeDescription: TypeDescription) extends ClassVisitor(Opcodes.ASM5, cv) {
  override def visitMethod(access: Int, name: String, desc: String, signature: String, exceptions: Array[String]): MethodVisitor = {
    val mv = super.visitMethod(access, name, desc, signature, exceptions)
    if (!name.startsWith("<init>")) return mv
    if ((Opcodes.ACC_PUBLIC & access) == 0) return mv // skipping non public methods
    ConstructorReturnAdapter(typeDescription.getInternalName, mv, access, name, desc)
  }
}

object RunnableConstructorVisitor {
  def apply(cv: ClassVisitor, typeDescription: TypeDescription) = new RunnableConstructorVisitor(cv, typeDescription)
}

class ConstructorReturnAdapter private (className: String, mv: MethodVisitor, access: Int, name: String, desc: String) extends AdviceAdapter(Opcodes.ASM5, mv, access, name, desc) {

  override def onMethodExit(opcode: Int): Unit = {
    if (opcode != Opcodes.ATHROW) {
      initializeTraceContext()
    }
  }

  private def initializeTraceContext(): Unit = {
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitFieldInsn(Opcodes.GETSTATIC, "kamon/trace/TraceContextAware$", "MODULE$", "Lkamon/trace/TraceContextAware$;")
    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "kamon/trace/TraceContextAware$", "default", "()Lkamon/trace/TraceContextAware;", false)
    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "kamon/trace/TraceContextAware", "traceContext", "()Lkamon/trace/TraceContext;", true)
    mv.visitFieldInsn(Opcodes.PUTFIELD, className, "traceContext", "Lkamon/trace/TraceContext;")
  }
}

object ConstructorReturnAdapter {
  def apply(internalName: String, mv: MethodVisitor, access: Int, name: String, desc: String) = new ConstructorReturnAdapter(internalName, mv, access, name, desc)
}

