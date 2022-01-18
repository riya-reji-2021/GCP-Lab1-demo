package com.example.demo;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import jdk.jfr.Description;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.windowing.*;
import org.apache.beam.sdk.values.*;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PubSubToBigQuery {
    static final TupleTag<CommonLog> parsedMessages = new TupleTag<CommonLog>() {
    };
    static final TupleTag<String> unparsedMessages = new TupleTag<String>() {
    };

    private static final Logger LOG = LoggerFactory.getLogger(PubSubToBigQuery.class);


    public interface Options extends DataflowPipelineOptions {
        @Description("Window duration length, in seconds")
        Integer getWindowDuration();
        void setWindowDuration(Integer windowDuration);

        @org.apache.beam.sdk.options.Description("BigQuery table name")
        String getOutputTableName();
        void setOutputTableName(String outputTableName);

        @org.apache.beam.sdk.options.Description("PubSub Subscription")
        String getSubscription();
        void setSubscription(String subscription);

        @Description("Window allowed lateness, in days")
        Integer getAllowedLateness();
        void setAllowedLateness(Integer allowedLateness);


    }

    public static final org.apache.beam.sdk.schemas.Schema rawSchema= Schema.
            builder()
            .addInt64Field("id")
            .addStringField("name")
            .addStringField("surname")
            .build();

    public static class PubsubMessageToCommonLog extends PTransform<PCollection<String>, PCollectionTuple> {
        @Override
        public PCollectionTuple expand(PCollection<String> input) {
            return input
                    .apply("JsonToCommonLog", ParDo.of(new DoFn<String, CommonLog>() {
                                @ProcessElement
                                public void processElement(ProcessContext context) {
                                    String json = context.element();
                                    Gson gson = new Gson();
                                    try {
                                        CommonLog commonLog = gson.fromJson(json, CommonLog.class);
                                        context.output(parsedMessages, commonLog);
                                    } catch (JsonSyntaxException e) {
                                        context.output(unparsedMessages, json);
                                    }

                                }
                            })
                            .withOutputTags(parsedMessages, TupleTagList.of(unparsedMessages)));


        }
    }


    public static void main(String[] args) {
        PipelineOptionsFactory.register(Options.class);
        Options options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(Options.class);
        run(options);
    }

    public static PipelineResult run(Options options) {

        // Create the pipeline
        Pipeline pipeline = Pipeline.create(options);
        LOG.info("Building pipeline...");


        PCollectionTuple transformOut =
                pipeline.apply("ReadPubSubMessages", PubsubIO.readStrings()
                                // Retrieve timestamp information from Pubsub Message attributes
                                .withTimestampAttribute("timestamp")
                                .fromSubscription(options.getSubscription()))
                        .apply("ConvertMessageToCommonLog", new PubsubMessageToCommonLog());

        // Write parsed messages to BigQuery
        transformOut
                // Retrieve parsed messages
                .get(parsedMessages)
                .apply("WindowByMinute", Window.<CommonLog>into(
                                FixedWindows.of(Duration.standardSeconds(options.getWindowDuration()))).withAllowedLateness(
                                Duration.standardDays(options.getAllowedLateness()))
                        .triggering(AfterWatermark.pastEndOfWindow()
                                .withLateFirings(AfterPane.elementCountAtLeast(1)))
                        .accumulatingFiredPanes())
                // update to Group.globally() after resolved: https://issues.apache.org/jira/browse/BEAM-10297
                // Only if supports Row output
                .apply("CountPerMinute", Combine.globally(Count.<CommonLog>combineFn())
                        .withoutDefaults())
                .apply("ConvertToRow", ParDo.of(new DoFn<Long, Row>() {
                    @ProcessElement
                    public void processElement(@Element Long views, OutputReceiver<Row> r, IntervalWindow window) {
                        Instant i = Instant.ofEpochMilli(window.end()
                                .getMillis());
                        Row row = Row.withSchema(rawSchema)
                                .addValues(views, i)
                                .build();
                        r.output(row);
                    }
                }))
                .setRowSchema(rawSchema)
                .apply("WriteToBQ", BigQueryIO.<Row>write().to(options.getOutputTableName())
                        .useBeamSchema()
                        .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                        .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));


        return pipeline.run();
    }

}
