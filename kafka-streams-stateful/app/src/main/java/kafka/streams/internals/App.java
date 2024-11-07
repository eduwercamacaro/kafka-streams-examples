/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package kafka.streams.internals;


import io.javalin.Javalin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class App {
    public static final String INPUT_TOPIC = "input-topic";
    public static final String OUTPUT_TOPIC = "output-topic";
    private static final Logger log = LoggerFactory.getLogger(App.class);


    public static void main(String[] args) throws Exception {
        final Properties props = AppConfig.streamsConfig(args);
        AdminClient client = AdminClient.create(props);
        NewTopic inputTopic = new NewTopic(INPUT_TOPIC, 15, (short) 1);
        NewTopic outputTopic = new NewTopic(OUTPUT_TOPIC, 15, (short) 1);
        client.createTopics(List.of(inputTopic, outputTopic));
        boolean usesDsl = AppConfig.usesDsl(args);
        Topology topology = usesDsl ? buildDslTopology() : buildProcessorTopology();
        final KafkaStreams streams = new KafkaStreams(topology, props);
        streams.setUncaughtExceptionHandler(exception -> StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT);
//        streams.setGlobalStateRestoreListener(new AppRestoreListener());
        HttpHandler httpHandler = new HttpHandler(streams, AppConfig.usesDsl(args));
        Javalin httpServer = Javalin.create().start(AppConfig.httpPort(AppConfig.httpConfig(args)));
        httpServer.get("/state", httpHandler::state);

        CountDownLatch latch = new CountDownLatch(2);
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                streams.close();
                latch.countDown();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread("http-server-shutdown-hook") {
            @Override
            public void run() {
                httpServer.close();
                latch.countDown();
            }
        });

        try {
            streams.start();
            latch.await();
            log.info("Done");
        } catch (final Throwable e) {
            System.exit(1);
        }
    }

    private static Topology buildDslTopology() {
        final StreamsBuilder builder = new StreamsBuilder();
        final KStream<String, Bytes> source = builder.stream(INPUT_TOPIC);
        Materialized<String, Bytes, KeyValueStore<Bytes, byte[]>> materialized = Materialized.
                <String, Bytes, KeyValueStore<Bytes, byte[]>>as("dsl-store")
                .withKeySerde(Serdes.String())
                .withValueSerde(Serdes.Bytes());
        source.groupByKey().aggregate(() -> new Bytes(new byte[0]), (key, value, aggregate) -> Util.randomBytes(), materialized);
        return builder.build();
    }

    private static Topology buildProcessorTopology() {
        final Topology topology = new Topology();
        StoreBuilder<KeyValueStore<String, Bytes>> processorStore = Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("processor-state"), Serdes.String(), Serdes.Bytes());
        topology.addSource("input", Serdes.String().deserializer(), Serdes.Bytes().deserializer(), INPUT_TOPIC);
        topology.addProcessor("random-value-processor", RandomValueProcessor::new, "input");
        topology.addStateStore(processorStore, "random-value-processor");
        return topology;
    }


}