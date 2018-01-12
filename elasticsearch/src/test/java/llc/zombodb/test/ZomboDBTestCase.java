/*
 * Portions Copyright 2015-2018 ZomboDB, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package llc.zombodb.test;

import llc.zombodb.highlight.AnalyzedField;
import llc.zombodb.query_parser.rewriters.QueryRewriter;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.flush.FlushAction;
import org.elasticsearch.action.admin.indices.flush.FlushRequestBuilder;
import org.elasticsearch.action.admin.indices.refresh.RefreshAction;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.stream.OutputStreamStreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.node.Node;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.*;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public abstract class ZomboDBTestCase {
    protected static final String DEFAULT_INDEX_NAME = "db.schema.table.index";

    private static final File HOME = new File("/tmp/zdb_es-" + System.currentTimeMillis());
    private static final File CONFIG = new File(HOME.getAbsolutePath() + "/config");
    private static final File LOGS = new File(HOME.getAbsolutePath() + "/logs");

    private static Node node;

    @BeforeClass
    public static void beforeClass() throws Exception {
        node = bootstrapElasticsearch();

        for (String indexName : new String[]{DEFAULT_INDEX_NAME, "db.schema.so_users.idxso_users", "db.schema.so_comments.idxso_comments", "db.schema.main_ft.idxmain_ft", "db.schema.main_vol.idxmain_vol", "db.schema.main_other.idxmain_other", "db.schema.main_other.idxmain", "db.schema.table2.index2"}) {
            createIndex(indexName);
        }
    }

    private static Node bootstrapElasticsearch() throws Exception {
        // copy our custom 'logging.xml' to the right place
        CONFIG.mkdirs();
        TestingHelper.copyFile(ZomboDBTestCase.class.getResourceAsStream("logging.yml"), new File(CONFIG.getAbsolutePath() + "/logging.yml"));

        Settings settings = Settings.builder()
                .put("http.enabled", false)
                .put("network.host", "127.0.0.1")
                .put("cluster.name", "ZomboDB_JUnit_Cluster")
                .put("node.name", "tester")
                .put("path.home", HOME.getAbsolutePath())
                .put("path.conf", CONFIG.getAbsolutePath())
                .put("path.logs", LOGS.getAbsolutePath())
                .put("transport.type", "local")
                .build();

        Environment env = new Environment(settings);

//        // make sure ES' logging system knows where to find our custom logging.xml
//        LogConfigurator.configure(env);

        // startup a standalone node to use for tests
        return new Node(settings).start();
    }

    private static void createIndex(String indexName) throws Exception {
        String settings = null;

        try {
            settings = resource(ZomboDBTestCase.class, indexName + "-mapping.json");
        } catch (FileNotFoundException ioe) {
            // ignore it -- lets us create shell indices
        }

        CreateIndexRequestBuilder builder = client().admin().indices().prepareCreate(indexName);
        if (settings != null)
            builder.setSource(settings);

        CreateIndexResponse response = client().admin().indices().create(builder.request()).get();
        response.writeTo(new OutputStreamStreamOutput(System.out));
        client().admin().indices().flush(new FlushRequestBuilder(client(), FlushAction.INSTANCE).setIndices(indexName).setForce(true).request()).get();
        client().admin().indices().refresh(new RefreshRequestBuilder(client(), RefreshAction.INSTANCE).setIndices(indexName).request()).get();
    }

    protected static Client client() {
        return node.client();
    }

    @AfterClass
    public static void afterClass() throws IOException {
        try {
            node.close();
        } finally {
            TestingHelper.deleteDirectory(HOME);
        }
    }

    protected static String resource(Class relativeTo, String name) throws Exception {
        InputStream data = relativeTo.getResourceAsStream(name);
        if (data == null)
            throw new FileNotFoundException(name);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(data, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            return sb.toString();
        }
    }


    protected QueryRewriter qr(String query) {
        return new QueryRewriter(null, client(), DEFAULT_INDEX_NAME, node.injector().getInstance(NamedXContentRegistry.class), query, false, false) { /* anonymous implementation */ };
    }

    protected void assertJson(String query, String expectedJson) throws Exception {
        String userJsonQuery = toJson(query);
        assertEquals(
                reparseViaQueryBuilder(expectedJson),
                reparseViaQueryBuilder(userJsonQuery)
        );
    }

    protected void assertAST(String query, String expectedAST) throws Exception {
        assertEquals(expectedAST.replaceAll("\r", "").trim(), toAST(query));
    }

    protected void assertSameJson(String query1, String query2) throws Exception {
        assertEquals(reparseViaQueryBuilder(toJson(query1)), reparseViaQueryBuilder(toJson(query2)));
    }

    protected void assertDifferentJson(String query1, String query2) throws Exception {
        assertNotEquals(toJson(query1), toJson(query2));
    }

    protected String toJson(String query) {
        return qr(query).rewriteQuery().toString();
    }

    protected String reparseViaQueryBuilder(String s) {
        if (s == null || s.trim().length() == 0)
            return "";

        try {
            NamedXContentRegistry registry = node.injector().getInstance(NamedXContentRegistry.class);
            QueryParseContext context = new QueryParseContext(JsonXContent.jsonXContent.createParser(registry, s));
            return context.parseInnerQueryBuilder().orElse(null).toString();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private void cleanJson(Map m) {
        Object boost = m.get("boost");
        if (boost instanceof Number && ((Number) boost).floatValue() != 0.0f)
            m.remove("boost");
        m.remove("disable_coord");
        m.remove("adjust_pure_negative");
        for (Object value : m.values()) {
            if (value instanceof Map)
                cleanJson((Map) value);
            else if (value instanceof List)
                cleanJson((List) value);
        }
    }

    private void cleanJson(List values) {
        for (Object value : values)
            if (value instanceof Map)
                cleanJson((Map) value);
            else if (value instanceof List)
                cleanJson((List) value);
    }

    protected String toAST(String query) {
        return qr(query).dumpAsString().replaceAll("\r", "").trim();
    }

    protected void sortHighlightTokens(List<AnalyzedField.Token> highlights) {
        Collections.sort(highlights, new Comparator<AnalyzedField.Token>() {
            @Override
            public int compare(AnalyzedField.Token o1, AnalyzedField.Token o2) {
                int cmp = o1.getFieldName().compareTo(o2.getFieldName());
                return cmp == 0 ? o1.getPosition() - o2.getPosition() : cmp;
            }
        });
    }


}
