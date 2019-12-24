package com.google.allenday.genomics.core.processing.other;

import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.model.ReadGroupMetaData;
import com.google.allenday.genomics.core.model.ReferenceDatabase;
import com.google.allenday.genomics.core.processing.VcfToBqService;
import com.google.allenday.genomics.core.utils.ResourceProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VcfToBqFn extends DoFn<KV<KV<ReadGroupMetaData, ReferenceDatabase>, String>, KV<KV<ReadGroupMetaData, ReferenceDatabase>, String>> {

    private Logger LOG = LoggerFactory.getLogger(VcfToBqFn.class);

    private VcfToBqService vcfToBqService;
    private ResourceProvider resourceProvider;
    private FileUtils fileUtils;

    public VcfToBqFn(VcfToBqService vcfToBqService, FileUtils fileUtils) {
        this.vcfToBqService = vcfToBqService;
        this.fileUtils = fileUtils;
    }

    @Setup
    public void setUp() {
        resourceProvider = ResourceProvider.initialize();
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
        LOG.info(String.format("Start of Vcf To Bq: %s", c.element().toString()));

        KV<KV<ReadGroupMetaData, ReferenceDatabase>, String> input = c.element();
        ReferenceDatabase referenceDatabase = input.getKey().getValue();
        ReadGroupMetaData readGroupMetaData = input.getKey().getKey();
        String vcfUri = input.getValue();

        if (readGroupMetaData == null || vcfUri == null || referenceDatabase == null) {
            LOG.error("Data error");
            LOG.error("referenceDatabase: " + referenceDatabase);
            LOG.error("readGroupMetaData: " + readGroupMetaData);
            LOG.error("vcfUri: " + vcfUri);
            return;
        }
        Pair<Boolean, String> result = vcfToBqService.convertVcfFileToBq(resourceProvider, referenceDatabase.getDbName(), vcfUri);

        if (result.getValue0()) {
            c.output(KV.of(input.getKey(), vcfUri));
        }
    }
}