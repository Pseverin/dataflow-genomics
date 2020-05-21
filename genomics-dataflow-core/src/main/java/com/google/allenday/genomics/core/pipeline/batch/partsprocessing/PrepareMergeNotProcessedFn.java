package com.google.allenday.genomics.core.pipeline.batch.partsprocessing;

import com.google.allenday.genomics.core.utils.FileUtils;
import com.google.allenday.genomics.core.gcp.GcsService;
import com.google.allenday.genomics.core.model.FileWrapper;
import com.google.allenday.genomics.core.model.SampleRunMetaData;
import com.google.allenday.genomics.core.model.SraSampleId;
import com.google.allenday.genomics.core.model.SamRecordsChunkMetadataKey;
import com.google.allenday.genomics.core.reference.ReferenceDatabaseSource;
import com.google.cloud.storage.BlobId;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class PrepareMergeNotProcessedFn extends DoFn<KV<SraSampleId, Iterable<SampleRunMetaData>>,
        KV<SamRecordsChunkMetadataKey, KV<ReferenceDatabaseSource, List<FileWrapper>>>> {

    private Logger LOG = LoggerFactory.getLogger(PrepareMergeNotProcessedFn.class);

    private GcsService gcsService;

    private FileUtils fileUtils;
    private List<String> references;

    private StagingPathsBulder stagingPathsBulder;
    private String allReferencesDirGcsUri;

    public PrepareMergeNotProcessedFn(FileUtils fileUtils, List<String> references,
                                      StagingPathsBulder stagingPathsBulder,
                                      String allReferencesDirGcsUri) {
        this.fileUtils = fileUtils;
        this.references = references;
        this.stagingPathsBulder = stagingPathsBulder;
        this.allReferencesDirGcsUri = allReferencesDirGcsUri;
    }

    @Setup
    public void setUp() {
        gcsService = GcsService.initialize(fileUtils);
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
        KV<SraSampleId, Iterable<SampleRunMetaData>> input = c.element();

        @Nonnull
        SraSampleId sraSampleId = input.getKey();

        Iterable<SampleRunMetaData> geneSampleMetaDataIterable = input.getValue();

        for (String ref : references) {
            BlobId blobIdMerge = stagingPathsBulder.buildMergedBlobId(sraSampleId.getValue(), ref);
            boolean mergeExists = gcsService.isExists(blobIdMerge);
            if (!mergeExists) {
                boolean redyToMerge = true;
                List<FileWrapper> fileWrappers = new ArrayList<>();
                for (SampleRunMetaData geneSampleRunMetaData : geneSampleMetaDataIterable) {
                    BlobId blobIdSort = stagingPathsBulder.buildSortedBlobId(geneSampleRunMetaData.getRunId(), ref);
                    boolean sortExists = gcsService.isExists(blobIdSort);
                    String uriFromBlob = gcsService.getUriFromBlob(blobIdSort);
                    fileWrappers.add(FileWrapper.fromBlobUri(uriFromBlob,
                            new FileUtils().getFilenameFromPath(uriFromBlob)));
                    if (!sortExists) {
                        redyToMerge = false;
                        LOG.info(String.format("Not ready to merge: %s", sraSampleId.getValue()));
                    }
                }
                if (redyToMerge) {
                    ReferenceDatabaseSource referenceDatabaseSource =
                            new ReferenceDatabaseSource.ByNameAndUriSchema(ref, allReferencesDirGcsUri);
                    c.output(KV.of(new SamRecordsChunkMetadataKey(sraSampleId, referenceDatabaseSource.getName()),
                            KV.of(referenceDatabaseSource, fileWrappers)));
                }
            }
        }
    }
}