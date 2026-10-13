package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class CurseForgeUploadsTest {
    @TempDir File directory
    File metadataFile
    File receipts
    Map metadata

    @BeforeEach
    void prepare() {
        receipts = new File(directory, 'receipts')
        receipts.mkdirs()
        Map record = [targets: ['26.3-fabric'], curseforge: [display_name: 'cbbg 1.5.0+mc26.3-fabric']]
        (['artifact'] + CurseForgeUploads.LEGACY_ATTACHMENTS).each { kind ->
            File file = new File(directory, kind + '.jar')
            file.text = kind
            record[kind] = [path: file.name, sha256: CandidateFiles.sha256(file)]
        }
        metadata = [release: 'v1.5.0', source_commit: 'abc', records: [record]]
        metadataFile = new File(directory, 'publication.json')
        write(metadataFile, metadata)
    }

    private static void write(File file, Map value) { file.text = JsonOutput.toJson(value) }

    private Map plan(boolean resuming = false, boolean confirmedUnattempted = false, Map evidence = [:]) {
        CurseForgeUploads.plan(metadataFile, receipts, directory, '26.3-fabric', resuming, confirmedUnattempted, evidence)
    }

    private static Map skippedUpload() {
        [run_id: '123', pages: [[jobs: [[run_id: 123, name: 'publish (26.3-fabric)', status: 'completed',
                steps: [[name: 'Upload to CurseForge', status: 'completed', conclusion: 'skipped']]]]]]]
    }

    private void legacy() {
        write(new File(receipts, 'publication.json'), metadata)
        write(new File(receipts, 'curseforge-result.json'), [release: metadata.release,
              source_commit: metadata.source_commit, targets: metadata.records[0].targets,
              artifact: metadata.records[0].artifact, file_id: '9126989',
              metadata_sha256: CandidateFiles.sha256(new File(receipts, 'publication.json'))])
    }

    @Test
    void newFamilyIncludesOnlyMainSourcesAndEvidence() {
        Map result = plan()
        assertEquals('', result.parent_file_id)
        assertEquals(new File(directory, 'artifact.jar').absolutePath, result.file_path)
        assertEquals(['sources.jar', 'evidence.jar'],
                result.additional_files.readLines().collect { new File(it).name })
        assertEquals('', result.receipt)
        assertEquals('false', result.manual_release)
    }

    @Test
    void legacyMainIsReusedWithoutUploadingItAgain() {
        legacy()
        Map result = plan()
        assertEquals('9126989', result.parent_file_id)
        assertEquals(new File(directory, 'sources.jar').absolutePath, result.file_path)
        assertEquals([new File(directory, 'evidence.jar').absolutePath], result.additional_files.readLines())
        assertFalse(result.additional_files.contains('artifact.jar'))
        assertEquals('false', result.manual_release)
    }

    @Test
    void mainPlanAndAcceptedMainSelectOnlyAutomaticAttachments() {
        write(new File(receipts, 'curseforge-main-plan.json'), plan())
        legacy()
        Map attachments = plan(true)
        assertEquals('9126989', attachments.parent_file_id)
        assertEquals('false', attachments.manual_release)
        assertEquals('sources.jar', new File(attachments.file_path).name)
        write(new File(receipts, 'curseforge-plan.json'), attachments)
        write(new File(receipts, 'curseforge-action-receipt.json'), [parentID: 9126989])
        Map resumed = plan(true)
        assertEquals(attachments.file_path, resumed.file_path)
        assertEquals(attachments.additional_files, resumed.additional_files)
        assertEquals('false', resumed.manual_release)
        assertFalse(resumed.additional_files.contains('artifact.jar'))
    }

    @Test
    void missingMainAcceptanceCannotBeOverriddenAfterUploadingStarted() {
        write(new File(receipts, 'curseforge-main-plan.json'), plan())
        assertThrows(GradleException) { plan(true) }
        assertThrows(GradleException) { plan(true, true, skippedUpload()) }
        Map skipped = skippedUpload()
        skipped.pages[0].jobs[0].steps[0].name = 'Upload main file to CurseForge'
        assertEquals('', plan(true, true, skipped).parent_file_id)
        skipped.pages[0].jobs[0].steps[0].conclusion = 'failure'
        assertThrows(GradleException) { plan(true, true, skipped) }
    }

    @Test
    void changedMainPlanIsRejectedEvenWithAnAcceptedMain() {
        Map original = plan()
        original.record.curseforge.display_name = 'different'
        write(new File(receipts, 'curseforge-main-plan.json'), original)
        legacy()
        assertThrows(GradleException) { plan(true) }
    }

    @Test
    void minecraftVersionsKeepSeparateParentsAndAttachments() {
        Map second = CandidateFiles.read(metadataFile).records[0]
        second.targets = ['26.2-fabric']
        second.curseforge.display_name = 'cbbg 1.5.0+mc26.2-fabric'
        Publication.uploadKinds(second).each { kind ->
            File file = new File(directory, '26.2-' + kind + '.jar')
            file.text = kind
            second[kind] = [path: file.name, sha256: CandidateFiles.sha256(file)]
        }
        metadata.records.add(second)
        write(metadataFile, metadata)
        metadata.records.eachWithIndex { record, index ->
            File saved = new File(receipts, record.targets[0])
            saved.mkdirs()
            write(new File(saved, 'publication.json'), metadata)
            write(new File(saved, 'curseforge-result.json'), [release: metadata.release,
                    source_commit: metadata.source_commit, targets: record.targets, artifact: record.artifact,
                    file_id: (123 + index).toString(),
                    metadata_sha256: CandidateFiles.sha256(new File(saved, 'publication.json'))])
            Map selected = CurseForgeUploads.plan(metadataFile, receipts, directory, record.targets[0], true)
            assertEquals((123 + index).toString(), selected.parent_file_id)
            assertEquals(record.sources.path, new File(selected.file_path).name)
            assertEquals('false', selected.manual_release)
            assertFalse(selected.additional_files.contains('utilities'))
            assertFalse(selected.additional_files.contains('library'))
        }
    }

    @Test
    void legacyMainAcceptsPreviousChangelogFormat() {
        Map fixture = CandidateFixture.create(new File(directory, 'candidate'))
        String notes = '### Fixed\n\n#### Minecraft 26.3\n\n- Example fix.\n\n<!-- cbbg-candidate-run: 123 -->\n'
        String previous = ChangelogNotes.forTargets(notes, [fixture.target])
        String current = ChangelogNotes.forPublication(notes, [fixture.target])
        metadata.release_notes = notes
        metadata.records[0].modrinth = [changelog: previous]
        metadata.records[0].curseforge.changelog = previous
        write(metadataFile, metadata)
        legacy()
        metadata.records[0].modrinth.changelog = current
        metadata.records[0].curseforge.changelog = current
        write(metadataFile, metadata)
        Map resumed = CurseForgeUploads.plan(metadataFile, receipts, directory,
                '26.3-fabric', true, false, [:], fixture.root)
        assertEquals('9126989', resumed.parent_file_id)
        assertThrows(GradleException) { plan(true) }
        File submittedFile = new File(receipts, 'publication.json')
        Map submitted = CandidateFiles.read(submittedFile)
        resumed.record = submitted.records[0]
        write(new File(receipts, 'curseforge-plan.json'), resumed)
        write(new File(receipts, 'curseforge-action-receipt.json'), [parentID: 9126989])
        assertEquals('9126989', CurseForgeUploads.plan(metadataFile, receipts, directory,
                '26.3-fabric', true, false, [:], fixture.root).parent_file_id)
        assertEquals(previous, CandidateFiles.read(submittedFile).records[0].curseforge.changelog)
        metadata.records[0].curseforge.changelog = current.replace('Example fix.', 'Different fix.')
        write(metadataFile, metadata)
        assertThrows(GradleException) {
            CurseForgeUploads.plan(metadataFile, receipts, directory,
                    '26.3-fabric', true, false, [:], fixture.root)
        }
    }

    @Test
    void changedPublicationAndFilesRejectLegacyReceipts() {
        legacy()
        metadata.records[0].curseforge.display_name = 'changed'
        write(metadataFile, metadata)
        assertThrows(GradleException) { plan() }
    }

    @Test
    void legacyAttachmentReceiptsRequireReconciliation() {
        legacy()
        File file = new File(receipts, 'curseforge-result.json')
        Map saved = CandidateFiles.read(file)
        saved.sources_file_id = '123'
        write(file, saved)
        assertThrows(GradleException) { plan() }
    }

    @Test
    void separateLegacyAttachmentReceiptsRequireReconciliation() {
        legacy()
        Map saved = CandidateFiles.read(new File(receipts, 'curseforge-result.json'))
        CurseForgeUploads.LEGACY_ATTACHMENTS.each { kind ->
            File attachment = new File(receipts, 'curseforge-' + kind.replace('_', '-') + '-result.json')
            write(attachment, saved + [file_id: '123'])
            GradleException error = assertThrows(GradleException) { plan() }
            assertTrue(error.message.contains('reconcile'), error.message)
            assertTrue(attachment.delete())
        }
    }

    @Test
    void attachmentReceiptWithoutMainReceiptCannotStartAnotherUpload() {
        write(new File(receipts, 'curseforge-sources-result.json'),
                [targets: ['26.3-fabric'], file_id: '123'])
        assertThrows(GradleException) { plan() }
    }

    @Test
    void separateLegacyAttachmentsCannotBeHiddenByAnActionReceipt() {
        Map original = plan()
        write(new File(receipts, 'curseforge-plan.json'), original)
        write(new File(receipts, 'curseforge-action-receipt.json'), [parentID: 123])
        write(new File(receipts, 'curseforge-sources-result.json'),
                [targets: ['26.3-fabric'], file_id: '456'])
        assertThrows(GradleException) { plan() }
    }

    @Test
    void anotherTargetsAttachmentReceiptDoesNotBlockTheSelectedTarget() {
        legacy()
        write(new File(receipts, 'curseforge-sources-result.json'),
                [targets: ['26.2-fabric'], file_id: '123'])
        assertEquals('9126989', plan().parent_file_id)
    }

    @Test
    void resumeWithoutReceiptsRequiresConfirmationBeforeStartingANewFamily() {
        GradleException error = assertThrows(GradleException) { plan(true) }
        assertTrue(error.message.contains('No usable CurseForge receipt'), error.message)
        Map confirmed = plan(true, true)
        assertEquals(new File(directory, 'artifact.jar').absolutePath, confirmed.file_path)
        assertTrue(confirmed.confirmed_unattempted)
    }

    @Test
    void incompletePlansCannotBeIgnoredOrOverridden() {
        write(new File(receipts, 'curseforge-plan.json'), plan())
        [false, true].each { confirmation ->
            GradleException error = assertThrows(GradleException) { plan(true, confirmation) }
            assertTrue(error.message.contains('missing its action receipt'), error.message)
        }
        assertThrows(GradleException) { plan() }
    }

    @Test
    void confirmedPlanCanResumeWhenTheUploaderWasSkipped() {
        Map original = plan()
        write(new File(receipts, 'curseforge-plan.json'), original)
        Map result = plan(true, true, skippedUpload())
        assertEquals(original.file_path, result.file_path)
        assertEquals(original.additional_files, result.additional_files)
        assertEquals('', result.parent_file_id)
        assertEquals('', result.receipt)
        assertTrue(result.confirmed_unattempted)
        assertThrows(GradleException) { plan(true, false, skippedUpload()) }
    }

    @Test
    void incompletePlansRequireSkippedUploadEvidenceForEveryAttempt() {
        write(new File(receipts, 'curseforge-plan.json'), plan())
        List<Closure> changes = [
                { it.pages[0].jobs[0].steps[0].conclusion = 'failure' },
                { it.pages[0].jobs[0].steps[0].conclusion = 'success' },
                { it.pages[0].jobs[0].steps[0].status = 'in_progress' },
                { it.pages[0].jobs[0].steps.clear() },
                { it.pages[0].jobs[0].status = 'in_progress' },
                { it.pages[0].jobs[0].run_id = 456 },
                { it.pages[0].jobs[0].name = 'publish (26.2-fabric)' },
                { it.pages.clear() },
                { it.pages.add([jobs: [[run_id: 123, name: 'publish (26.3-fabric)', status: 'completed',
                        steps: [[name: 'Upload to CurseForge', status: 'completed', conclusion: 'failure']]]]]) }
        ]
        changes.each { change ->
            Map evidence = skippedUpload()
            change(evidence)
            assertThrows(GradleException) { plan(true, true, evidence) }
        }
    }

    @Test
    void skippedUploadDoesNotPermitChangedPlansOrExistingParents() {
        Map original = plan()
        File file = new File(receipts, 'curseforge-plan.json')
        [source_commit: 'changed', release: 'v1.5.1', parent_file_id: '123', receipt: '/old/receipt.json',
         record: [:], additional_files: 'changed'].each { key, value ->
            write(file, original + [(key): value])
            assertThrows(GradleException) { plan(true, true, skippedUpload()) }
        }
    }

    @Test
    void actionReceiptWithoutItsPlanRequiresReconciliation() {
        write(new File(receipts, 'curseforge-action-receipt.json'), [parentID: 123])
        assertThrows(GradleException) { plan(true, true) }
    }

    @Test
    void unattemptedConfirmationCannotOverrideAnExistingReceipt() {
        legacy()
        assertEquals('9126989', plan(true).parent_file_id)
        assertThrows(GradleException) { plan(true, true) }
    }

    @Test
    void unattemptedConfirmationRequiresResumeMode() {
        assertThrows(GradleException) { plan(false, true) }
    }

    @Test
    void resumePreservesOriginalFamilyAndDelegatesAcceptedStatesToUploader() {
        Map original = plan()
        original.remove('manual_release')
        write(new File(receipts, 'curseforge-plan.json'), original)
        write(new File(receipts, 'curseforge-action-receipt.json'), [parentID: 123, selectedParentID: null])
        Map resumed = plan(true)
        assertEquals(original.file_path, resumed.file_path)
        assertEquals(original.additional_files, resumed.additional_files)
        assertEquals('', resumed.parent_file_id)
        assertEquals('auto', resumed.manual_release)
        assertEquals(new File(receipts, 'curseforge-action-receipt.json').absolutePath, resumed.receipt)
        original.file_path = '/tmp/unrelated.jar'
        write(new File(receipts, 'curseforge-plan.json'), original)
        assertThrows(GradleException) { plan() }
    }

    @Test
    void changedSourceRejectsActionReceipts() {
        Map original = plan()
        write(new File(receipts, 'curseforge-plan.json'), original)
        write(new File(receipts, 'curseforge-action-receipt.json'), [parentID: 123])
        metadata.source_commit = 'changed'
        write(metadataFile, metadata)
        assertThrows(GradleException) { plan() }
    }

    @Test
    void changedFileBytesCannotBeSubmitted() {
        new File(directory, 'sources.jar').text = 'changed'
        assertThrows(GradleException) { plan() }
    }
}
