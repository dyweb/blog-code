package lucenejoin;

import lombok.AllArgsConstructor;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.join.*;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows how to use lucene index time join using the shirt example from https://blog.mikemccandless.com/2012/01/searching-relational-content-with.html.
 */
public class LuceneIndexTimeJoinTest {
    /**
     * A shirt brand with its in stock specs.
     */
    @AllArgsConstructor
    private static class Shirt {
        /**
         * Brand of shirt, e.g. wolf, dog, gaocedidi, at15
         */
        private final String name;

        /**
         * Different color of size of this brand.
         */
        private final List<ShirtSpec> specs;

        /**
         * Only include name. does NOT include the specs.
         *
         * @return lucene document to write to {@link IndexWriter}
         */
        public Document toDocument() {
            Document document = new Document();
            // docType field is used for distinguish parent and child
            document.add(new Field("docType", "shirt", StringField.TYPE_NOT_STORED));
            document.add(new Field("name", name, StringField.TYPE_STORED));
            return document;
        }

        /**
         * Convert to flatten document, prefix child object fields.
         *
         * @return lucene document to write to {@link IndexWriter}
         */
        public Document toFlatten() {
            Document document = toDocument();
            for (ShirtSpec spec : specs) {
                // Yes, you can add multiple value to same field in a doc
                document.add(new Field("spec.sku", spec.sku, StringField.TYPE_STORED));
                document.add(new Field("spec.color", spec.color, StringField.TYPE_STORED));
                document.add(new Field("spec.size", spec.size, StringField.TYPE_STORED));
            }
            return document;
        }
    }

    /**
     * Color and size of a shirt.
     */
    @AllArgsConstructor
    private static class ShirtSpec {
        /**
         * 1, 2, 3, 4 ... just some assigned number
         */
        private final String sku;
        /**
         * red, blue
         */
        private final String color;
        /**
         * small, medium, large
         */
        private final String size;

        public Document toDocument() {
            Document document = new Document();
            document.add(new Field("sku", sku, StringField.TYPE_STORED));
            document.add(new Field("color", color, StringField.TYPE_STORED));
            document.add(new Field("size", size, StringField.TYPE_STORED));
            return document;
        }
    }

    /**
     * Flatten shirt, search result is not expected from customer perspective ... customer obsession!
     */
    @Test
    public void testSearchFlatUnexpectedMatch() throws IOException {
        // Use in memory directory so we don't need to clean it up.
        // If you want to use the GUI tool, luke, change this to FSDirectory.open("/tmp/my/awesome/path");
        Directory directory = new ByteBuffersDirectory();
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
        IndexWriter indexWriter = new IndexWriter(directory, indexWriterConfig);

        // Insert docs
        List<Shirt> shirts = makeShirts();
        for (Shirt shirt : shirts) {
            indexWriter.addDocument(shirt.toFlatten()); // Flatten doc
        }
        indexWriter.commit(); // flush the index

        // Search
        IndexReader indexReader = DirectoryReader.open(indexWriter);
        indexWriter.close();
        IndexSearcher indexSearcher = new IndexSearcher(indexReader);

        // Query for shirt that has color blue and size medium
        BooleanQuery query = new BooleanQuery.Builder()
                .add(new BooleanClause(new TermQuery(new Term("spec.color", "blue")), BooleanClause.Occur.MUST))
                .add(new BooleanClause(new TermQuery(new Term("spec.size", "medium")), BooleanClause.Occur.MUST))
                .build();

        TopDocs topDocs = indexSearcher.search(query, 10);
        System.out.println("color=blue & size=medium hit on flatten " + topDocs.totalHits.value);
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document document = indexSearcher.doc(scoreDoc.doc);
            System.out.println("name=" + document.get("name") +
                    " sku=" + String.join(",", document.getValues("spec.sku")) +
                    " color=" + String.join(",", document.getValues("spec.color")) +
                    " size=" + String.join(",", document.getValues("spec.size")));
        }
        // NOTE: We found shirts while we should not (from customer perspective)
        // There is no blue & medium in either wolf or dog, wolf only has blue & small or green & medium
        // color=blue & size=medium hit on flatten 2
        // name=wolf sku=1,3,4 color=blue,green,yellow size=small,medium,large
        // name=dog sku=11,12 color=blue,red size=small,medium
    }

    /**
     * Index outer & inner/parent & child objects as different lucene documents in same index.
     * Use BlockJoin to apply filter on both outer & inner document.
     * Search result is expected.
     *
     * @see #testSearchNestedMatch() for matched
     */
    @Test
    public void testSearchNestedNoMatch() throws IOException {
        // See previous test for more comment on lucene usage
        Directory directory = new ByteBuffersDirectory();
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
        IndexWriter indexWriter = new IndexWriter(directory, indexWriterConfig);

        // Insert docs as block
        List<Shirt> shirts = makeShirts();
        for (Shirt shirt : shirts) {
            List<Document> block = new ArrayList<>();
            // First insert children
            for (ShirtSpec spec : shirt.specs) {
                block.add(spec.toDocument());
            }
            // Add parent in the end
            block.add(shirt.toDocument());
            // Write children and parent in one transaction
            indexWriter.addDocuments(block);
        }
        indexWriter.commit();

        // Search
        IndexReader indexReader = DirectoryReader.open(indexWriter);
        indexWriter.close();
        IndexSearcher indexSearcher = new IndexSearcher(indexReader);

        // Filter out parent document using this query
        BitSetProducer parentsFilter = new QueryBitSetProducer(new TermQuery(new Term("docType", "shirt")));
        // Same query on color and size, no spec. prefix because children are now separated documents
        BooleanQuery childQuery = new BooleanQuery.Builder()
                .add(new BooleanClause(new TermQuery(new Term("color", "blue")), BooleanClause.Occur.MUST))
                .add(new BooleanClause(new TermQuery(new Term("size", "medium")), BooleanClause.Occur.MUST))
                .build();

        // Join matched children to their parent document, search result is parent document, NOT matched children.
        ToParentBlockJoinQuery childJoinQuery = new ToParentBlockJoinQuery(childQuery, parentsFilter, ScoreMode.Avg);
        Query parentQuery = new TermQuery(new Term("name", "wolf"));

        BooleanQuery fullQuery = new BooleanQuery.Builder()
                .add(new BooleanClause(parentQuery, BooleanClause.Occur.MUST))
                .add(new BooleanClause(childJoinQuery, BooleanClause.Occur.MUST))
                .build();

        TopDocs topDocs = indexSearcher.search(fullQuery, 10);
        System.out.println("name=wolf & color=blue & size=medium hit on nested " + topDocs.totalHits.value); // 0
        // No match because base on spec, there is no blue & medium under wolf brand
    }

    /**
     * Query that actually matches some child doc.
     */
    @Test
    public void testSearchNestedMatch() throws IOException {
        IndexSearcher indexSearcher = makeNestedSearcher();

        // Filter out parent document using this query
        BitSetProducer parentsFilter = new QueryBitSetProducer(new TermQuery(new Term("docType", "shirt")));
        // Should match some child
        BooleanQuery childQuery = new BooleanQuery.Builder()
                .add(new BooleanClause(new TermQuery(new Term("color", "blue")), BooleanClause.Occur.MUST))
                .add(new BooleanClause(new TermQuery(new Term("size", "small")), BooleanClause.Occur.MUST))
                .build();

        // Join matched children to their parent document, search result is parent document, NOT matched children.
        ToParentBlockJoinQuery childJoinQuery = new ToParentBlockJoinQuery(childQuery, parentsFilter, ScoreMode.Avg);
        Query parentQuery = new TermQuery(new Term("name", "wolf"));

        BooleanQuery fullQuery = new BooleanQuery.Builder()
                .add(new BooleanClause(parentQuery, BooleanClause.Occur.MUST))
                .add(new BooleanClause(childJoinQuery, BooleanClause.Occur.MUST))
                .build();

        TopDocs topDocs = indexSearcher.search(fullQuery, 10);
        System.out.println("name=wolf & color=blue & size=small hit on nested " + topDocs.totalHits.value); // 0
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = indexSearcher.doc(scoreDoc.doc);
            System.out.println("parent doc=" + scoreDoc.doc + " name=" + doc.get("name"));

            // Get the actual child docs that matches under this parent doc
            ParentChildrenBlockJoinQuery childrenQuery = new ParentChildrenBlockJoinQuery(parentsFilter,
                    childQuery, scoreDoc.doc);
            TopDocs matchingChildren = indexSearcher.search(childrenQuery, 10);
            for (ScoreDoc child : matchingChildren.scoreDocs) {
                Document childDoc = indexSearcher.doc(child.doc);
                System.out.println("child doc=" + child.doc + " color=" + childDoc.get("color") + " size=" + childDoc.get("size"));
            }
        }
        // NOTE: parent doc id is larger than child, it is actually increase from 0, see makeShirts
        // name=wolf & color=blue & size=small hit on nested 1
        // parent doc=3 name=wolf
        // child doc=0 color=blue size=small
    }

    private List<Shirt> makeShirts() {
        Shirt s1 = new Shirt("wolf", List.of(
                new ShirtSpec("1", "blue", "small"),
                new ShirtSpec("3", "green", "medium"),
                new ShirtSpec("4", "yellow", "large")
        ));
        Shirt s2 = new Shirt("dog", List.of(
                new ShirtSpec("11", "blue", "small"),
                new ShirtSpec("12", "red", "medium")
        ));
        return List.of(s1, s2);
    }

    private IndexSearcher makeNestedSearcher() throws IOException {
        // See previous test for more comment on lucene usage
        Directory directory = new ByteBuffersDirectory();
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
        IndexWriter indexWriter = new IndexWriter(directory, indexWriterConfig);

        // Insert docs as block
        List<Shirt> shirts = makeShirts();
        for (Shirt shirt : shirts) {
            List<Document> block = new ArrayList<>();
            // First insert children
            for (ShirtSpec spec : shirt.specs) {
                block.add(spec.toDocument());
            }
            // Add parent in the end
            block.add(shirt.toDocument());
            // Write children and parent in one transaction
            indexWriter.addDocuments(block);
        }
        indexWriter.commit();

        // Search
        IndexReader indexReader = DirectoryReader.open(indexWriter);
        indexWriter.close();
        return new IndexSearcher(indexReader);
    }
}
