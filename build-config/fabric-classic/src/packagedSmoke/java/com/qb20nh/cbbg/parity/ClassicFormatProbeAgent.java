package com.qb20nh.cbbg.parity;

import com.qb20nh.cbbg.gametest.ReleaseMapping;
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

/** Masks selected blending features after the candidate queries them from the Vulkan driver. */
public final class ClassicFormatProbeAgent {
  private ClassicFormatProbeAgent() {}

  public static void premain(String arguments, Instrumentation instrumentation)
      throws MalformedURLException {
    URL candidate = Path.of(System.getProperty("cbbg.test.jar")).toUri().toURL();
    String target =
        ReleaseMapping.className("com.qb20nh.cbbg.render.vulkanmod.VulkanModMainPass")
            .replace('.', '/');
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> type,
              ProtectionDomain domain,
              byte[] bytes) {
            if (!target.equals(name)
                || domain == null
                || domain.getCodeSource() == null
                || !candidate.equals(domain.getCodeSource().getLocation())) {
              return null;
            }
            return injectFormatMask(bytes);
          }
        });
  }

  private static byte[] injectFormatMask(byte[] bytes) {
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    int[] sites = {0};
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
                  public void visitMethodInsn(
                      int opcode,
                      String owner,
                      String name,
                      String descriptor,
                      boolean isInterface) {
                    boolean query =
                        owner.equals("org/lwjgl/vulkan/VK10")
                            && name.equals("vkGetPhysicalDeviceFormatProperties")
                            && descriptor.equals(
                                "(Lorg/lwjgl/vulkan/VkPhysicalDevice;ILorg/lwjgl/vulkan/VkFormatProperties;)V");
                    // Save the format and output structure across the original call.
                    if (query) super.visitInsn(Opcodes.DUP2_X1);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    if (query) {
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC,
                          "com/qb20nh/cbbg/parity/ClassicVulkanSmoke",
                          "maskFormatFeatures",
                          "(ILjava/lang/Object;)V",
                          false);
                      sites[0]++;
                    }
                  }
                };
              }
            },
            0);
    System.out.println("CBBG_FORMAT_AGENT querySites=" + sites[0]);
    return sites[0] == 1 ? writer.toByteArray() : null;
  }
}
