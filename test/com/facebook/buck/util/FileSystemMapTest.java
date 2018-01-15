/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.FileSystemMap.Entry;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class FileSystemMapTest {

  private FileSystemMap.ValueLoader<Boolean> loader = path -> true;

  @Test
  public void testPutLeafNodeWithEmptyTrie() {
    Path path = Paths.get("foo/bar/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(path, true);
    FileSystemMap.Entry<Boolean> foo = fsMap.root.subLevels.get("foo");
    assertNotNull(foo);
    FileSystemMap.Entry<Boolean> bar = foo.subLevels.get("bar");
    assertNotNull(bar);
    FileSystemMap.Entry<Boolean> file = bar.subLevels.get("HelloWorld.java");
    assertNotNull(file);
    assertTrue(file.getWithoutLoading());
    assertEquals(fsMap.map.size(), 1);
    assertTrue(fsMap.map.get(PathFragments.pathToFragment(path)).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodeWithNonEmptyTrie() {
    Path path = Paths.get("foo/bar/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);

    // Set up the trie with one child and ensure the trie is in the state we want.
    fsMap.put(Paths.get("usr"), true);
    assertNotNull(fsMap.root.subLevels.get("usr"));

    // Write the new entry and check data structure state.
    fsMap.put(path, true);
    assertEquals(fsMap.root.subLevels.get("usr").size(), 0);
    Entry<Boolean> file =
        fsMap.root.subLevels.get("foo").subLevels.get("bar").subLevels.get("HelloWorld.java");
    assertTrue(file.getWithoutLoading());
    assertEquals(fsMap.map.size(), 2);
    assertTrue(fsMap.map.get(PathFragments.pathToFragment(path)).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodeAlreadyInserted() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);

    // Insert the entry into the map, verify resulting state.
    fsMap.put(path, true);
    FileSystemMap.Entry<Boolean> usr = fsMap.root.subLevels.get("usr");
    Entry<Boolean> helloWorld = fsMap.map.get(PathFragments.pathToFragment(path));
    assertTrue(helloWorld.getWithoutLoading());
    assertSame(helloWorld, fsMap.root.subLevels.get("usr").subLevels.get("HelloWorld.java"));

    // Insert the entry again with a different value.
    fsMap.put(path, false);

    // We check that the object hasn't been reinstantiated => reference is the same.
    assertSame(fsMap.root.subLevels.get("usr"), usr);
    assertSame(usr.subLevels.get("HelloWorld.java"), helloWorld);
    Entry<Boolean> helloWorldEntry = usr.subLevels.get("HelloWorld.java");
    assertNotNull(helloWorldEntry);
    assertFalse(helloWorldEntry.getWithoutLoading());
    assertEquals(fsMap.map.size(), 1);
    assertFalse(fsMap.map.get(PathFragments.pathToFragment(path)).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodePathPartiallyInserted() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);

    // Insert another entry with the same initial path.
    fsMap.put(Paths.get("usr/OtherPath"), false);
    FileSystemMap.Entry<Boolean> usr = fsMap.root.subLevels.get("usr");

    // Now insert the entry.
    fsMap.put(path, true);

    // We check that the object hasn't been reinstantiated => reference is the same.
    assertSame(fsMap.root.subLevels.get("usr"), usr);
    Entry<Boolean> file = usr.subLevels.get("HelloWorld.java");
    assertNotNull(file);
    assertTrue(file.getWithoutLoading());
    assertEquals(fsMap.map.size(), 2);
    assertTrue(fsMap.map.get(PathFragments.pathToFragment(path)).getWithoutLoading());
  }

  @Test
  public void testRemovePathThatExistsAndIntermediateNodesAreRemovedToo() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);

    // Insert the item and ensure data structure is correct.
    fsMap.put(path, true);
    assertTrue(
        fsMap.root.subLevels.get("usr").subLevels.get("HelloWorld.java").getWithoutLoading());

    // Remove the item and check intermediate nodes are deleted.
    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels.containsKey("usr"));
    assertEquals(fsMap.map.size(), 0);
  }

  @Test
  public void testRemovePathThatExistsAndIntermediateIsNotRemovedButValueIsRemoved() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(Paths.get("usr"), true);
    fsMap.put(path, true);
    fsMap.put(Paths.get("usr/Yo.java"), true);

    fsMap.remove(path);
    assertNull(fsMap.root.subLevels.get("usr").getWithoutLoading());
    assertFalse(fsMap.root.subLevels.get("usr").subLevels.containsKey("HelloWorld.java"));
    assertTrue(fsMap.root.subLevels.get("usr").subLevels.containsKey("Yo.java"));
    assertEquals(fsMap.map.size(), 1);
    assertTrue(fsMap.map.get(PathFragment.create("usr/Yo.java")).getWithoutLoading());
  }

  @Test
  public void testRemovePathThatDoesntExist() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(Paths.get("usr"), true);
    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels.containsKey("usr"));
    assertEquals(0, fsMap.map.size());
    assertFalse(fsMap.map.containsKey(PathFragments.pathToFragment(path)));
  }

  @Test
  public void testRemoveIntermediateNode() {
    Path path = Paths.get("usr");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(path, true);
    fsMap.put(Paths.get("usr/HelloWorld.java"), true);
    fsMap.put(Paths.get("usr/Yo.java"), true);
    assertEquals(3, fsMap.map.size());

    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels.containsKey("usr"));
    assertFalse(fsMap.map.containsKey(PathFragments.pathToFragment(path)));
    assertFalse(fsMap.map.containsKey(PathFragment.create("usr/HelloWorld.java")));
    assertFalse(fsMap.map.containsKey(PathFragment.create("usr/Yo.java")));
  }

  @Test
  public void testRemoveAll() {
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(Paths.get("usr/HelloWorld.java"), true);
    fsMap.put(Paths.get("usr/Yo.java"), true);
    assertEquals(fsMap.root.size(), 1);
    assertEquals(fsMap.map.size(), 2);

    fsMap.removeAll();
    assertEquals(fsMap.root.size(), 0);
    assertEquals(fsMap.map.size(), 0);
  }

  @Test
  public void testRemoveAllWithEmptyTrie() {
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.removeAll();
    assertEquals(fsMap.root.size(), 0);
    assertEquals(fsMap.map.size(), 0);
  }

  @Test
  public void testGetWithPathThatExists() throws IOException {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(path, true);
    assertTrue(fsMap.get(path));
    assertEquals(fsMap.root.size(), 1);
    assertTrue(fsMap.map.get(PathFragments.pathToFragment(path)).getWithoutLoading());
  }

  @Test
  public void testGetAtRootLevelWithPathThatExists() throws IOException {
    Path path = Paths.get("HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(path, true);
    assertTrue(fsMap.get(path));
    assertEquals(fsMap.root.size(), 1);
    assertTrue(fsMap.map.get(PathFragments.pathToFragment(path)).getWithoutLoading());
  }

  @Test
  public void testGetWithPathDoesntExist() throws IOException {
    Path path = Paths.get("usr/GoodbyeCruelWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);

    // Put a path that does exist.
    fsMap.put(Paths.get("usr/HelloWorld.java"), true);

    // Fetch a value that does not exist, see that it is loaded and cached in the map.
    Boolean entry = fsMap.get(path);
    assertNotNull(entry);
    assertTrue(entry);
    assertEquals(fsMap.map.size(), 2);
    assertTrue(fsMap.map.get(PathFragments.pathToFragment(path)).getWithoutLoading());
  }
}
