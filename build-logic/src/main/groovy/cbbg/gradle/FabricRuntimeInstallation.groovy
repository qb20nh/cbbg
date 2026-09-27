package cbbg.gradle

import org.gradle.api.GradleException

class FabricRuntimeInstallation {
    static void ensure(File runtime, String target, String profile, Closure<Integer> install) {
        File receipt = new File(runtime, 'cbbg-install-receipt.json')
        boolean resume = runtime.exists()
        if (resume) {
            Map recorded = readReceipt(receipt, target, profile)
            if (recorded.installed) return
        }
        runtime.parentFile.mkdirs()
        int exit = install.call(resume)
        if (exit != 0) throw new GradleException('Runtime installation failed for ' + profile)
        if (!readReceipt(receipt, target, profile).installed) {
            throw new GradleException('Runtime installation is incomplete for ' + profile)
        }
    }

    private static Map readReceipt(File receipt, String target, String profile) {
        if (!receipt.isFile()) throw new GradleException('Missing installation receipt: ' + receipt)
        Object recorded = CandidateFiles.read(receipt)
        if (!(recorded instanceof Map) || recorded.target != target || recorded.profile != profile ||
                !(recorded.installed instanceof Boolean)) {
            throw new GradleException('Installation receipt does not match ' + profile)
        }
        (Map) recorded
    }
}
