package cbbg.gradle

import org.gradle.api.GradleException

/** Select a complete upload family, or resume a previously submitted family. */
class CurseForgeUploads {
    static final List<String> LEGACY_ATTACHMENTS = ['sources', 'utilities', 'utilities_sources',
                                                  'library', 'library_sources', 'evidence']

    static Map plan(File metadataFile, File receiptsDirectory, File assets, String target,
                    boolean resuming = false, boolean confirmedUnattempted = false, Map resumeEvidence = [:]) {
        Map metadata = CandidateFiles.read(metadataFile)
        List records = metadata.records.findAll { it.targets[0] == target }
        if (records.size() != 1) throw new GradleException('Select one CurseForge artifact owner: ' + target)
        if (confirmedUnattempted && !resuming) {
            throw new GradleException('Unattempted target confirmation requires a CurseForge resume run')
        }
        Map record = records[0]
        List<File> plans = []
        List<File> unattemptedPlans = []
        List<File> legacy = []
        List<File> legacyAttachments = []
        receiptsDirectory.eachFileRecurse { file ->
            if (file.name == 'curseforge-plan.json' && CandidateFiles.read(file).target == target) {
                if (!new File(file.parentFile, 'curseforge-action-receipt.json').isFile()) {
                    if (!confirmedUnattempted || !uploaderWasSkipped(resumeEvidence, target)) {
                        throw new GradleException('CurseForge plan for ' + target +
                                ' is missing its action receipt; recovery requires confirmation and a recorded skipped upload')
                    }
                    unattemptedPlans.add(file)
                } else {
                    plans.add(file)
                }
            }
            if (file.name == 'curseforge-action-receipt.json' &&
                    !new File(file.parentFile, 'curseforge-plan.json').isFile()) {
                throw new GradleException('CurseForge action receipt is missing its plan; reconcile the upload before retrying')
            }
            if (file.name == 'curseforge-result.json' && CandidateFiles.read(file).targets == record.targets) legacy.add(file)
            if (file.name ==~ /curseforge-.+-result\.json/ &&
                    CandidateFiles.read(file).targets?.contains(target)) legacyAttachments.add(file)
        }
        if (legacyAttachments) {
            throw new GradleException('Legacy attachment receipts for ' + target + '; reconcile them before retrying: ' +
                    legacyAttachments.collect { it.name }.sort().join(', '))
        }
        if (plans.size() + unattemptedPlans.size() > 1 || legacy.size() > 1 || (unattemptedPlans && legacy)) {
            throw new GradleException('Conflicting CurseForge receipts for ' + target)
        }
        if (resuming && !plans && !legacy && !confirmedUnattempted) {
            throw new GradleException('No usable CurseForge receipt for ' + target +
                    '; check the previous run and CurseForge dashboard before confirming it was never attempted')
        }
        if (confirmedUnattempted && (plans || legacy)) {
            throw new GradleException('Unattempted target confirmation conflicts with the saved CurseForge receipt for ' + target)
        }
        String receipt = ''
        String parent = ''
        Map previousPlan = null
        if (plans || unattemptedPlans) {
            Map saved = CandidateFiles.read((plans ?: unattemptedPlans)[0])
            if (saved.release != metadata.release || saved.source_commit != metadata.source_commit || saved.record != record) {
                throw new GradleException('CurseForge upload inputs changed; inspect the saved receipt before retrying')
            }
            previousPlan = saved
            if (unattemptedPlans && (saved.parent_file_id != '' || saved.receipt != '')) {
                throw new GradleException('Unattempted plan refers to an existing CurseForge upload; reconcile it before retrying')
            }
            parent = saved.parent_file_id
            if (!(parent instanceof String) || (parent && !(parent ==~ /[1-9][0-9]*/))) {
                throw new GradleException('Invalid CurseForge parent ID')
            }
            if (plans) receipt = new File(plans[0].parentFile, 'curseforge-action-receipt.json').absolutePath
        }
        if (legacy && !plans) {
            Map saved = CandidateFiles.read(legacy[0])
            File submittedFile = new File(legacy[0].parentFile, 'publication.json')
            Map submitted = CandidateFiles.read(submittedFile)
            if (saved.release != metadata.release || saved.source_commit != metadata.source_commit ||
                    saved.artifact != record.artifact || saved.metadata_sha256 != CandidateFiles.sha256(submittedFile) ||
                    submitted.release != metadata.release || submitted.source_commit != metadata.source_commit ||
                    submitted.records.find { it.targets == record.targets } != record) {
                throw new GradleException('CurseForge receipt differs from the selected publication')
            }
            if (!(saved.file_id instanceof String) || !(saved.file_id ==~ /[1-9][0-9]*/)) {
                throw new GradleException('Invalid CurseForge parent ID')
            }
            if (LEGACY_ATTACHMENTS.any { saved[it + '_file_id'] != null }) {
                throw new GradleException('Legacy receipt includes attachments; reconcile them before migration')
            }
            parent = saved.file_id
        }
        List<String> kinds = Publication.uploadKinds(record).findAll {
            record[it] instanceof Map && (it != 'artifact' || !parent)
        }
        List<String> files = kinds.collect { CandidateFiles.checked(assets, (Map) record[it]).absolutePath }
        if (files.isEmpty()) throw new GradleException('No CurseForge files selected')
        Map result = [target: target, release: metadata.release, source_commit: metadata.source_commit, record: record,
         file_path: files.first(), additional_files: files.drop(1).join('\n'), parent_file_id: parent,
         display_name: parent ? new File(files.first()).name : record.curseforge.display_name,
         receipt: receipt]
        if (confirmedUnattempted) result.confirmed_unattempted = true
        if (previousPlan && ['file_path', 'additional_files', 'display_name'].any { previousPlan[it] != result[it] }) {
            throw new GradleException('CurseForge upload file selection changed')
        }
        result
    }

    private static boolean uploaderWasSkipped(Map evidence, String target) {
        if (!(evidence.run_id instanceof String) || !(evidence.run_id ==~ /[1-9][0-9]*/) ||
                !(evidence.pages instanceof List) || !evidence.pages || evidence.pages.any {
                    !(it instanceof Map) || !(it.jobs instanceof List) || it.jobs.any { !(it instanceof Map) }
                }) return false
        List jobs = evidence.pages.collectMany { it.jobs }.findAll { it.name == 'publish (' + target + ')' }
        jobs && jobs.every { job ->
            List steps = job.steps instanceof List ? job.steps.findAll {
                it instanceof Map && it.name == 'Upload to CurseForge'
            } : []
            job.run_id?.toString() == evidence.run_id && job.status == 'completed' && steps.size() == 1 &&
                    steps[0].status == 'completed' && steps[0].conclusion == 'skipped'
        }
    }
}
