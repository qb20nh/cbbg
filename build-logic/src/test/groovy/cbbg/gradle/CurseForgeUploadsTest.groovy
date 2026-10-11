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
        (['artifact'] + CurseForgeUploads.ATTACHMENTS).each { kind ->
            File file = new File(directory, kind + '.jar')
            file.text = kind
            record[kind] = [path: file.name, sha256: CandidateFiles.sha256(file)]
        }
        metadata = [release: 'v1.5.0', source_commit: 'abc', records: [record]]
        metadataFile = new File(directory, 'publication.json')
        write(metadataFile, metadata)
    }

    private static void write(File file, Map value) { file.text = JsonOutput.toJson(value) }

    private Map plan() { CurseForgeUploads.plan(metadataFile, receipts, directory, '26.3-fabric') }

    private void legacy() {
        write(new File(receipts, 'publication.json'), metadata)
        write(new File(receipts, 'curseforge-result.json'), [release: metadata.release,
              source_commit: metadata.source_commit, targets: metadata.records[0].targets,
              artifact: metadata.records[0].artifact, file_id: '9126989',
              metadata_sha256: CandidateFiles.sha256(new File(receipts, 'publication.json'))])
    }

    @Test
    void newFamilyIncludesEveryAttachment() {
        Map result = plan()
        assertEquals('', result.parent_file_id)
        assertEquals(new File(directory, 'artifact.jar').absolutePath, result.file_path)
        assertEquals(6, result.additional_files.readLines().size())
        assertEquals('', result.receipt)
    }

    @Test
    void legacyMainIsReusedWithoutUploadingItAgain() {
        legacy()
        Map result = plan()
        assertEquals('9126989', result.parent_file_id)
        assertEquals(new File(directory, 'sources.jar').absolutePath, result.file_path)
        assertEquals(5, result.additional_files.readLines().size())
        assertFalse(result.additional_files.contains('artifact.jar'))
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
        CurseForgeUploads.ATTACHMENTS.each { kind ->
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
    void resumePreservesOriginalFamilyAndDelegatesAcceptedStatesToUploader() {
        Map original = plan()
        write(new File(receipts, 'curseforge-plan.json'), original)
        write(new File(receipts, 'curseforge-action-receipt.json'), [parentID: 123, selectedParentID: null])
        Map resumed = plan()
        assertEquals(original.file_path, resumed.file_path)
        assertEquals(original.additional_files, resumed.additional_files)
        assertEquals('', resumed.parent_file_id)
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
