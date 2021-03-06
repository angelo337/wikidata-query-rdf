package org.wikidata.query.rdf.tool.change;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.io.Resources.getResource;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.wikidata.query.rdf.tool.HttpClientUtils.buildHttpClient;
import static org.wikidata.query.rdf.tool.HttpClientUtils.buildHttpClientRetryer;
import static org.wikidata.query.rdf.tool.HttpClientUtils.getHttpProxyHost;
import static org.wikidata.query.rdf.tool.HttpClientUtils.getHttpProxyPort;
import static org.wikidata.query.rdf.tool.RdfRepositoryForTesting.url;
import static org.wikidata.query.rdf.tool.change.KafkaPoller.buildKafkaPoller;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.jetty.client.HttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.wikidata.query.rdf.tool.change.events.ChangeEvent;
import org.wikidata.query.rdf.tool.exception.RetryableException;
import org.wikidata.query.rdf.tool.rdf.client.RdfClient;
import org.wikidata.query.rdf.tool.wikibase.WikibaseRepository.Uris;

import com.codahale.metrics.MetricRegistry;
import com.github.charithe.kafka.EphemeralKafkaBroker;
import com.github.charithe.kafka.KafkaJunitRule;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;

@SuppressWarnings("boxing")
public class KafkaPollerIntegrationTest {

    public static final String CREATE_TOPIC = "mediawiki.revision-create";
    public static final String DELETE_TOPIC = "mediawiki.page-delete";
    public static final String UNDELETE_TOPIC = "mediawiki.page-undelete";
    public static final String CHANGE_TOPIC = "mediawiki.page-properties-change";
    private static final String DOMAIN = "acme.test";
    private static final long BEGIN_DATE = 1518207153000L;

    @ClassRule
    public static KafkaJunitRule kafkaRule = new KafkaJunitRule(EphemeralKafkaBroker.create()).waitForStartup();

    private KafkaPoller poller;

    @Before
    public void setupPoller() throws URISyntaxException {
        poller = createPoller();
    }

    @After
    public void cleanupPoller() {
        if (poller != null) {
            poller.close();
        }
    }

    @Test
    public void receiveValidCreateEvent() throws RetryableException, IOException {
        sendEvent(CREATE_TOPIC, "create-event.json");
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(1);
        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q123");
        assertThat(change.revision()).isEqualTo(1L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-02-19T13:31:23Z"));
    }

    @Test
    public void receiveRealCreateEvent() throws RetryableException, IOException {
        sendEvent(CREATE_TOPIC, "create-event-full.json");
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(1);
        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q20672616");
        assertThat(change.revision()).isEqualTo(62295L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-01-21T16:38:20Z"));
    }

    @Test
    public void receivePageDeleteEvent() throws RetryableException, IOException {
        sendEvent(DELETE_TOPIC, "page-delete.json");
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(1);
        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q47462581");
        assertThat(change.revision()).isEqualTo(-1L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-01-19T18:53:59Z"));
    }

    @Test
    public void receivePageUndeleteEvent() throws RetryableException, IOException {
        sendEvent(UNDELETE_TOPIC, "page-undelete.json");
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(1);
        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q32451604");
        assertThat(change.revision()).isEqualTo(565767L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-01-21T09:30:46Z"));
    }

    @Ignore("temporarily disabled prop-change for performance reasons")
    @Test
    public void receivePropChangeEvent() throws RetryableException, IOException {
        sendEvent(CHANGE_TOPIC, "prop-change.json");
        sendEvent(CHANGE_TOPIC, "prop-change-wb.json"); // this one will be ignored
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(1);
        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q7359206");
        assertThat(change.revision()).isEqualTo(-1L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-01-23T01:32:14Z"));
    }

    @Test
    public void receiveClusteredEvents() throws RetryableException, IOException, URISyntaxException {
        cleanupPoller();
        poller = createPoller(ImmutableList.of("north", "south"));
        sendEvent("north." + CREATE_TOPIC, "create-event-full.json");
        sendEvent("south." + DELETE_TOPIC, "page-delete.json");
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(2);

        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q20672616");
        assertThat(change.revision()).isEqualTo(62295L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-01-21T16:38:20Z"));

        change = changes.get(1);

        assertThat(change.entityId()).isEqualTo("Q47462581");
        assertThat(change.revision()).isEqualTo(-1L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-01-19T18:53:59Z"));
    }

    @Test
    public void receiveOtherEvents() throws RetryableException, IOException {
        sendEvent(CREATE_TOPIC, "rc-domain.json");
        sendEvent(CREATE_TOPIC, "create-event.json");
        sendEvent(CREATE_TOPIC, "rc-namespace.json");
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(1);
        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q123");
        assertThat(change.revision()).isEqualTo(1L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-02-19T13:31:23Z"));
    }

    private void sendEvent(String topic, String eventFile) throws IOException {
        String eventData = load(eventFile);
        try (KafkaProducer<String, String> producer = kafkaRule.helper().createStringProducer()) {
            producer.send(new ProducerRecord<>(topic, eventData));
        }
    }

    private String randomConsumer() {
        return DOMAIN + Instant.now().toEpochMilli() + Math.round(Math.random() * 1000);
    }

    private KafkaPoller createPoller() throws URISyntaxException {
        return createPoller(emptyList());
    }

    private KafkaPoller createPoller(Collection<String> clusterNames) throws URISyntaxException {
        String servers = "localhost:" + kafkaRule.helper().kafkaPort();
        Uris uris = Uris.fromString("https://acme.test");
        URI root = uris.builder().build();
        KafkaOffsetsRepository kafkaOffsetsRepository = new RdfKafkaOffsetsRepository(root, null);
        return buildKafkaPoller(servers, randomConsumer(), clusterNames,
                uris, 5, Instant.now(), true, kafkaOffsetsRepository,
                new MetricRegistry());
    }


    private String load(String name) throws IOException {
        String prefix = this.getClass().getPackage().getName().replace(".", "/");
        return Resources.toString(getResource(prefix + "/events/" + name), UTF_8);
    }

    @Test
    public void readWriteOffsets() throws Exception {
        KafkaConsumer<String, ChangeEvent> consumer = mock(KafkaConsumer.class);
        Uris uris = Uris.fromString("https://acme.test").setEntityNamespaces(singleton(0L));

        Instant startTime = Instant.ofEpochMilli(BEGIN_DATE);
        Collection<String> topics = ImmutableList.of("topictest", "othertopic");

        when(consumer.partitionsFor(any())).thenAnswer(inv -> {
            String pName = inv.getArgumentAt(0, String.class);
            PartitionInfo pi = new PartitionInfo(pName, 0, null, null, null);
            return ImmutableList.of(pi);
        });

        HttpClient httpClient = buildHttpClient(getHttpProxyHost(), getHttpProxyPort());
        RdfClient rdfClient = new RdfClient(httpClient,
                url("/namespace/wdq/sparql"),
                buildHttpClientRetryer(),
                Duration.of(-1, SECONDS)
        );

        try {
            rdfClient.update("CLEAR ALL");
            cleanupPoller();
            KafkaOffsetsRepository kafkaOffsetsRepository = new RdfKafkaOffsetsRepository(uris.builder().build(), rdfClient);
            poller = new KafkaPoller(consumer, uris, startTime, 5, topics, kafkaOffsetsRepository,
                    true, new MetricRegistry());

            when(consumer.position(any())).thenReturn(1L, 2L, 3L, 4L);
            kafkaOffsetsRepository.store(poller.currentOffsets());

            Map<TopicPartition, OffsetAndTimestamp> offsets = kafkaOffsetsRepository.load(startTime);
            assertThat(offsets.get(new TopicPartition("topictest", 0)).offset()).isEqualTo(1L);
            assertThat(offsets.get(new TopicPartition("othertopic", 0)).offset()).isEqualTo(2L);

            kafkaOffsetsRepository.store(poller.currentOffsets());
            offsets = kafkaOffsetsRepository.load(startTime);
            assertThat(offsets.get(new TopicPartition("topictest", 0)).offset()).isEqualTo(3L);
            assertThat(offsets.get(new TopicPartition("othertopic", 0)).offset()).isEqualTo(4L);
        } finally {
            rdfClient.update("CLEAR ALL");
            httpClient.stop();
            if (poller != null) {
                poller.close();
            }
        }
    }

    @Test
    public void receiveCreateEventWithMs() throws RetryableException, IOException {
        sendEvent(CREATE_TOPIC, "create-event-ms.json");
        List<Change> changes = poller.firstBatch().changes();

        assertThat(changes).hasSize(1);
        Change change = changes.get(0);

        assertThat(change.entityId()).isEqualTo("Q123");
        assertThat(change.revision()).isEqualTo(5L);
        assertThat(change.timestamp()).isEqualTo(Instant.parse("2018-10-24T00:28:24.1623Z"));
    }
}
