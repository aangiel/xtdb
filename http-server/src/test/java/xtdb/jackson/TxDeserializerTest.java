package xtdb.jackson;

import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;
import xtdb.tx.Ops;
import xtdb.tx.Put;
import xtdb.tx.Tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TxDeserializerTest {

    private final ObjectMapper objectMapper;

    public TxDeserializerTest() {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("JsonLd-test");

        HashMap<Class<?>, JsonDeserializer<?>> deserializerMapping = new HashMap<>();
        deserializerMapping.put(Put.class, new PutDeserializer());
        deserializerMapping.put(Ops.class, new OpsDeserializer());
        deserializerMapping.put(Tx.class, new TxDeserializer());
        SimpleDeserializers deserializers = new SimpleDeserializers(deserializerMapping);
        module.setDeserializers(deserializers);

        objectMapper.registerModule(module);
        this.objectMapper = objectMapper;
    }

    @Test
    public void shouldDeserializeTx() throws IOException {
        // given
        String json =
                    """
                    {"tx_ops":[{"put":"docs","doc":{}}]}
                """;

        // when
        Object actual = objectMapper.readValue(json, Tx.class);

        // then
        ArrayList<Ops> ops = new ArrayList<Ops>();
        ops.add(Ops.put(Keyword.intern("docs"), Collections.emptyMap()));
        assertEquals(new Tx(ops, null, null), actual);
    }
}