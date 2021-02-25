// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.local;

import static com.google.firebase.firestore.testutil.TestUtil.assertDoesNotThrow;
import static com.google.firebase.firestore.testutil.TestUtil.deletedDoc;
import static com.google.firebase.firestore.testutil.TestUtil.doc;
import static com.google.firebase.firestore.testutil.TestUtil.key;
import static com.google.firebase.firestore.testutil.TestUtil.map;
import static com.google.firebase.firestore.testutil.TestUtil.path;
import static com.google.firebase.firestore.testutil.TestUtil.values;
import static com.google.firebase.firestore.testutil.TestUtil.version;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.google.firebase.database.collection.ImmutableSortedMap;
import com.google.firebase.firestore.core.Query;
import com.google.firebase.firestore.model.DocumentKey;
import com.google.firebase.firestore.model.MutableDocument;
import com.google.firebase.firestore.model.SnapshotVersion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * These are tests for any implementation of the RemoteDocumentCache interface.
 *
 * <p>To test a specific implementation of RemoteDocumentCache:
 *
 * <ol>
 *   <li>Subclass RemoteDocumentCacheTestCase
 *   <li>Override {@link #getPersistence}, creating a new implementation of Persistence
 * </ol>
 */
abstract class RemoteDocumentCacheTestCase {

  private Persistence persistence;
  private RemoteDocumentCache remoteDocumentCache;

  @Before
  public void setUp() {
    persistence = getPersistence();
    remoteDocumentCache = persistence.getRemoteDocumentCache();
  }

  @After
  public void tearDown() {
    persistence.shutdown();
  }

  abstract Persistence getPersistence();

  @Test
  public void testReadDocumentNotInCache() {
    assertFalse(get("a/b").isValidDocument());
  }

  @Test
  public void testSetAndReadDocument() {
    String[] paths = {"a/b", "a/b/c/d/e/f"};
    for (String path : paths) {
      MutableDocument written = addTestDocumentAtPath(path);
      MutableDocument read = get(path);
      assertEquals(written, read);
    }
  }

  @Test
  public void testSetAndReadSeveralDocuments() {
    String[] paths = {"a/b", "a/b/c/d/e/f"};
    Map<DocumentKey, MutableDocument> written = new HashMap<>();
    for (String path : paths) {
      written.put(DocumentKey.fromPathString(path), addTestDocumentAtPath(path));
    }

    Map<DocumentKey, MutableDocument> read = getAll(Arrays.asList(paths));
    assertEquals(written, read);
  }

  @Test
  public void testReadSeveralDocumentsIncludingMissingDocument() {
    String[] paths = {"foo/1", "foo/2"};
    Map<DocumentKey, MutableDocument> written = new HashMap<>();
    for (String path : paths) {
      written.put(DocumentKey.fromPathString(path), addTestDocumentAtPath(path));
    }
    written.put(DocumentKey.fromPathString("foo/nonexistent"), null);

    List<String> keys = new ArrayList<>(Arrays.asList(paths));
    keys.add("foo/nonexistent");
    written.put(
        key("foo/nonexistent"), new MutableDocument(key("foo/nonexistent"))); // Add invalid doc
    Map<DocumentKey, MutableDocument> read = getAll(keys);
    assertEquals(written, read);
  }

  // PORTING NOTE: this test only applies to Android, because it's the only platform where the
  // implementation of getAll might split the input into several queries.
  @Test
  public void testSetAndReadLotsOfDocuments() {
    // Make sure to force SQLite implementation to split the large query into several smaller ones.
    int lotsOfDocuments = 2000;
    List<String> paths = new ArrayList<>();
    Map<DocumentKey, MutableDocument> expected = new HashMap<>();
    for (int i = 0; i < lotsOfDocuments; i++) {
      String path = "foo/" + String.valueOf(i);
      paths.add(path);
      expected.put(DocumentKey.fromPathString(path), addTestDocumentAtPath(path));
    }

    Map<DocumentKey, MutableDocument> read = getAll(paths);
    assertEquals(expected, read);
  }

  @Test
  public void testSetAndReadDeletedDocument() {
    String path = "a/b";
    MutableDocument deletedDoc = deletedDoc(path, 42);
    add(deletedDoc, version(42));
    assertEquals(deletedDoc, get(path));
  }

  @Test
  public void testSetDocumentToNewValue() {
    String path = "a/b";
    MutableDocument written = addTestDocumentAtPath(path);

    MutableDocument newDoc = doc(path, 57, map("data", 5));
    add(newDoc, version(57));

    assertNotEquals(written, newDoc);
    assertEquals(newDoc, get(path));
  }

  @Test
  public void testRemoveDocument() {
    String path = "a/b";
    addTestDocumentAtPath(path);
    remove(path);
    assertFalse(get(path).isValidDocument());
  }

  @Test
  public void testRemoveNonExistentDocument() {
    assertDoesNotThrow(() -> remove("a/b"));
  }

  @Test
  public void testDocumentsMatchingQuery() {
    // TODO: This just verifies that we do a prefix scan against the
    // query path. We'll need more tests once we add index support.
    Map<String, Object> docData = map("data", 2);
    addTestDocumentAtPath("a/1");
    addTestDocumentAtPath("b/1");
    addTestDocumentAtPath("b/2");
    addTestDocumentAtPath("c/1");

    Query query = Query.atPath(path("b"));
    ImmutableSortedMap<DocumentKey, MutableDocument> results =
        remoteDocumentCache.getAllDocumentsMatchingQuery(query, SnapshotVersion.NONE);
    List<MutableDocument> expected = asList(doc("b/1", 42, docData), doc("b/2", 42, docData));
    // assertEquals(expected, values(results));
  }

  @Test
  public void testDocumentsMatchingQuerySinceReadTime() {
    Map<String, Object> docData = map("data", 2);
    addTestDocumentAtPath("b/old", /* updateTime= */ 1, /* readTime= */ 11);
    addTestDocumentAtPath("b/current", /* updateTime= */ 2, /*  readTime= = */ 12);
    addTestDocumentAtPath("b/new", /* updateTime= */ 3, /*  readTime= = */ 13);

    Query query = Query.atPath(path("b"));
    ImmutableSortedMap<DocumentKey, MutableDocument> results =
        remoteDocumentCache.getAllDocumentsMatchingQuery(query, version(12));
    List<MutableDocument> expected = asList(doc("b/new", 3, docData));
    assertEquals(expected, values(results));
  }

  @Test
  public void testDocumentsMatchingUsesReadTimeNotUpdateTime() {
    Map<String, Object> docData = map("data", 2);
    addTestDocumentAtPath("b/old", /* updateTime= */ 1, /* readTime= */ 2);
    addTestDocumentAtPath("b/new", /* updateTime= */ 2, /* readTime= */ 1);

    Query query = Query.atPath(path("b"));
    ImmutableSortedMap<DocumentKey, MutableDocument> results =
        remoteDocumentCache.getAllDocumentsMatchingQuery(query, version(1));
    List<MutableDocument> expected = asList(doc("b/old", 1, docData));
    assertEquals(expected, values(results));
  }

  private MutableDocument addTestDocumentAtPath(String path) {
    return addTestDocumentAtPath(path, 42, 42);
  }

  private MutableDocument addTestDocumentAtPath(String path, int updateTime, int readTime) {
    MutableDocument doc = doc(path, updateTime, map("data", 2));
    add(doc, version(readTime));
    return doc;
  }

  private void add(MutableDocument doc, SnapshotVersion readTime) {
    persistence.runTransaction("add entry", () -> remoteDocumentCache.add(doc, readTime));
  }

  private MutableDocument get(String path) {
    return remoteDocumentCache.get(key(path));
  }

  private Map<DocumentKey, MutableDocument> getAll(Iterable<String> paths) {
    List<DocumentKey> keys = new ArrayList<>();

    for (String path : paths) {
      keys.add(key(path));
    }

    return remoteDocumentCache.getAll(keys);
  }

  private void remove(String path) {
    persistence.runTransaction("remove entry", () -> remoteDocumentCache.remove(key(path)));
  }
}
