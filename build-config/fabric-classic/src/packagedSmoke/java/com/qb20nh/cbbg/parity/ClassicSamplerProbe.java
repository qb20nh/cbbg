package com.qb20nh.cbbg.parity;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Hooks the sampler and its cache in the locked VulkanMod artifact. */
final class ClassicSamplerProbe {
  private static final String FIXTURE = "com/qb20nh/cbbg/parity/ClassicVulkanSamplerFailure";
  private static final String IMAGE = "net/vulkanmod/vulkan/texture/VulkanImage";
  private static final String SAMPLER = "net/vulkanmod/vulkan/texture/SamplerManager";
  private static final String CACHE = "it/unimi/dsi/fastutil/shorts/Short2LongMap";
  private static final String CREATE =
      "(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"
          + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I";

  private ClassicSamplerProbe() {}

  static byte[] attach(String className, byte[] bytes) {
    boolean image = IMAGE.equals(className);
    if (!image && !SAMPLER.equals(className)) return null;
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    int[] sites = {0};
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9, writer) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String method, String type, String signature, String[] exceptions) {
                return new MethodVisitor(
                    Opcodes.ASM9, super.visitMethod(access, method, type, signature, exceptions)) {
                  @Override
                  public void visitFieldInsn(int opcode, String owner, String field, String type) {
                    super.visitFieldInsn(opcode, owner, field, type);
                    if (image
                        && method.equals("createTextureImage")
                        && opcode == Opcodes.PUTFIELD
                        && owner.equals(IMAGE)
                        && field.equals("mainImageView")
                        && type.equals("J")) {
                      super.visitVarInsn(Opcodes.ALOAD, 1);
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC,
                          FIXTURE,
                          "observeImage",
                          "(Ljava/lang/Object;)V",
                          false);
                      sites[0]++;
                    }
                  }

                  @Override
                  public void visitMethodInsn(
                      int opcode, String owner, String invoked, String type, boolean isInterface) {
                    if (!image
                        && opcode == Opcodes.INVOKEINTERFACE
                        && owner.equals(CACHE)
                        && invoked.equals("getOrDefault")
                        && type.equals("(SJ)J")) {
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC,
                          FIXTURE,
                          "samplerCache",
                          "(L" + CACHE + ";SJ)J",
                          false);
                      sites[0]++;
                    } else if (!image
                        && opcode == Opcodes.INVOKESTATIC
                        && owner.equals("org/lwjgl/vulkan/VK10")
                        && invoked.equals("vkCreateSampler")
                        && type.equals(CREATE)) {
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC,
                          FIXTURE,
                          "createSampler",
                          "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/nio/LongBuffer;)I",
                          false);
                      sites[0]++;
                    } else {
                      super.visitMethodInsn(opcode, owner, invoked, type, isInterface);
                    }
                  }
                };
              }
            },
            0);
    System.out.println("CBBG_SAMPLER_AGENT class=" + className + " hooks=" + sites[0]);
    return sites[0] == (image ? 1 : 2) ? writer.toByteArray() : null;
  }
}
