package com.google.allenday.genomics.core.processing.align;

import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.io.GCSService;
import com.google.allenday.genomics.core.io.TransformIoHandler;
import com.google.allenday.genomics.core.model.FileWrapper;
import com.google.allenday.genomics.core.model.SampleMetaData;
import com.google.allenday.genomics.core.model.ReferenceDatabase;
import com.google.allenday.genomics.core.reference.ReferencesProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class AlignFn extends DoFn<KV<KV<SampleMetaData, List<String>>, List<FileWrapper>>, KV<KV<SampleMetaData, ReferenceDatabase>, FileWrapper>> {

    private Logger LOG = LoggerFactory.getLogger(AlignFn.class);
    private GCSService gcsService;

    private AlignService alignService;
    private ReferencesProvider referencesProvider;
    private TransformIoHandler transformIoHandler;
    private FileUtils fileUtils;

    public AlignFn(AlignService alignService,
                   ReferencesProvider referencesProvider,
                   TransformIoHandler transformIoHandler,
                   FileUtils fileUtils) {
        this.alignService = alignService;
        this.referencesProvider = referencesProvider;
        this.transformIoHandler = transformIoHandler;
        this.fileUtils = fileUtils;
    }

    @Setup
    public void setUp() {
        gcsService = GCSService.initialize(fileUtils);
        alignService.setupMinimap2();
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
        LOG.info(String.format("Start of processing with input: %s", c.element().toString()));

        KV<SampleMetaData, List<String>> kvMetaDataRefs = c.element().getKey();
        SampleMetaData geneExampleMetaData = kvMetaDataRefs.getKey();
        List<String> referenceNames = kvMetaDataRefs.getValue();

        List<FileWrapper> fileWrapperList = c.element().getValue();

        if (geneExampleMetaData == null || fileWrapperList.size() == 0) {
            LOG.error("Data error");
            LOG.error("geneExampleMetaData: " + geneExampleMetaData);
            LOG.error("fileWrapperList.size(): " + fileWrapperList.size());
            return;
        }
        try {
            String workingDir = fileUtils.makeDirByCurrentTimestampAndSuffix(geneExampleMetaData.getRunId());
            try {
                List<String> srcFilesPaths = fileWrapperList.stream()
                        .map(geneData -> transformIoHandler.handleInputAsLocalFile(gcsService, geneData, workingDir))
                        .collect(Collectors.toList());

                for (String referenceName : referenceNames) {
                    Pair<ReferenceDatabase, String> referenceDatabaseAndFastaLocalPath = referencesProvider.findReference(gcsService, referenceName);
                    ReferenceDatabase referenceDatabase = referenceDatabaseAndFastaLocalPath.getValue0();
                    String localFastaPath = referenceDatabaseAndFastaLocalPath.getValue1();

                    String alignedSamPath = alignService.alignFastq(localFastaPath, srcFilesPaths,
                            workingDir, geneExampleMetaData.getRunId(), referenceName, geneExampleMetaData.getSraSample());
                    c.output(KV.of(KV.of(geneExampleMetaData, referenceDatabase), transformIoHandler.handleFileOutput(gcsService, alignedSamPath)));
                }
            } catch (IOException e) {
                LOG.error(e.getMessage());
                e.printStackTrace();
            } finally {
                fileUtils.deleteDir(workingDir);
            }
        } catch (RuntimeException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
        }
    }


}
