package com.qb20nh.cbbg.parity;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Routes the pinned VulkanMod image allocation and free calls through the test fixture. */
public final class ClassicVmaProbeAgent {
  private static final String FIXTURE = "com/qb20nh/cbbg/parity/ClassicVulkanAllocation";
  private static final String ALLOCATE =
      "(JLorg/lwjgl/vulkan/VkImageCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;"
          + "Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I";

  private ClassicVmaProbeAgent() {}

  public static void premain(String modPath, Instrumentation instrumentation)
      throws MalformedURLException {
    URL mod = Path.of(modPath).toUri().toURL();
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> type,
              ProtectionDomain domain,
              byte[] bytes) {
            if (!"net/vulkanmod/vulkan/memory/MemoryManager".equals(name)
                || domain == null
                || domain.getCodeSource() == null
                || !mod.equals(domain.getCodeSource().getLocation())) {
              return null;
            }
            return attachProbe(bytes);
          }
        });
  }

  private static byte[] attachProbe(byte[] bytes) {
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    int[] sites = new int[2];
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9, writer) {
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String name,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                return new MethodVisitor(
                    Opcodes.ASM9,
                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                  @Override
                  public void visitCode() {
                    super.visitCode();
                    if (name.equals("freeImage")
                        && descriptor.equals("(JJ)V")
                        && (access & Opcodes.ACC_STATIC) != 0) {
                      super.visitVarInsn(Opcodes.LLOAD, 0);
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC, FIXTURE, "observeFree", "(J)V", false);
                      sites[1]++;
                    }
                  }

                  @Override
                  public void visitMethodInsn(
                      int opcode,
                      String owner,
                      String name,
                      String descriptor,
                      boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC
                        && owner.equals("org/lwjgl/util/vma/Vma")
                        && name.equals("vmaCreateImage")
                        && descriptor.equals(ALLOCATE)) {
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC,
                          FIXTURE,
                          "createImage",
                          "(JLjava/lang/Object;Ljava/lang/Object;Ljava/nio/LongBuffer;"
                              + "Lorg/lwjgl/PointerBuffer;Ljava/lang/Object;)I",
                          false);
                      sites[0]++;
                    } else {
                      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                  }
                };
              }
            },
            0);
    System.out.println("CBBG_ALLOC_AGENT allocationSites=" + sites[0] + " freeSites=" + sites[1]);
    return sites[0] == 1 && sites[1] == 1 ? writer.toByteArray() : null;
  }
}
