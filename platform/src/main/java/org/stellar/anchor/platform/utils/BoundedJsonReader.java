package org.stellar.anchor.platform.utils;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;

/**
 * A {@link JsonReader} that refuses a body as soon as it reads past a limit, so the object graph
 * Gson builds while binding stays bounded no matter how small the bytes are. A byte cap alone does
 * not bound it: {@code {}} is two bytes and becomes a whole map.
 *
 * <p>Every value read through the reader counts as one node: each object, each array, and each
 * string, number, boolean and null. Member names do not count. Values Gson skips with {@link
 * #skipValue()} are not materialized and are not counted.
 *
 * <p>An instance holds its own counters and is not shared between requests or threads.
 */
public class BoundedJsonReader extends JsonReader {
  /** Limits the number of elements of one array: the root array or an array of the root object. */
  public static final class ArrayLimit {
    private final String member;
    private final int max;

    private ArrayLimit(String member, int max) {
      this.member = member;
      this.max = max;
    }

    /** Limits the elements of the root array. */
    public static ArrayLimit root(int max) {
      return new ArrayLimit(null, max);
    }

    /** Limits the elements of the array held by the named member of the root object. */
    public static ArrayLimit member(String member, int max) {
      return new ArrayLimit(member, max);
    }
  }

  private final int maxNodes;
  private final int maxDepth;
  private final ArrayLimit arrayLimit;

  private int nodes;
  private int depth;
  // For each open container: whether it is an array, and how many values it has received.
  private boolean[] isArray = new boolean[16];
  private int[] elements = new int[16];
  // For each open object: whether its next token is a member name (not yet read as a value).
  private boolean[] expectName = new boolean[16];
  // The depth (1-based) of the array that arrayLimit applies to, or 0 when none is open.
  private int limitedDepth;
  private boolean rootIsObject;
  private String rootMemberName;

  public BoundedJsonReader(Reader in, int maxNodes, int maxDepth, ArrayLimit arrayLimit) {
    super(in);
    this.maxNodes = maxNodes;
    this.maxDepth = maxDepth;
    this.arrayLimit = arrayLimit;
  }

  /** The number of values counted so far. */
  public int nodeCount() {
    return nodes;
  }

  @Override
  public void beginArray() throws IOException {
    super.beginArray();
    checkDepth();
    countValue();
    push(true);
  }

  @Override
  public void beginObject() throws IOException {
    super.beginObject();
    checkDepth();
    countValue();
    push(false);
  }

  @Override
  public void endArray() throws IOException {
    super.endArray();
    pop();
  }

  @Override
  public void endObject() throws IOException {
    super.endObject();
    pop();
  }

  @Override
  public String nextName() throws IOException {
    String name = super.nextName();
    if (depth > 0) {
      expectName[depth - 1] = false;
    }
    trackName(name);
    return name;
  }

  private void trackName(String name) {
    if (depth == 1) {
      rootMemberName = name;
    }
  }

  @Override
  public String nextString() throws IOException {
    String value = super.nextString();
    if (depth > 0 && !isArray[depth - 1] && expectName[depth - 1]) {
      // Gson's map adapter promotes each member name to a value and reads it through nextString().
      // A name is not a value: it is tracked like nextName() and not counted.
      expectName[depth - 1] = false;
      trackName(value);
    } else {
      countValue();
    }
    return value;
  }

  @Override
  public boolean nextBoolean() throws IOException {
    boolean value = super.nextBoolean();
    countValue();
    return value;
  }

  @Override
  public void nextNull() throws IOException {
    super.nextNull();
    countValue();
  }

  @Override
  public double nextDouble() throws IOException {
    double value = super.nextDouble();
    countValue();
    return value;
  }

  @Override
  public long nextLong() throws IOException {
    long value = super.nextLong();
    countValue();
    return value;
  }

  @Override
  public int nextInt() throws IOException {
    int value = super.nextInt();
    countValue();
    return value;
  }

  /** Counts one value that has just been consumed, in the container that is currently open. */
  private void countValue() {
    if (depth > 0) {
      expectName[depth - 1] = true;
    }
    if (depth > 0 && isArray[depth - 1] && depth == limitedDepth) {
      if (++elements[depth - 1] > arrayLimit.max) {
        // the value just consumed is the one over the limit; its children are never read
        throw new JsonArrayLimitException(arrayLimit.member, arrayLimit.max);
      }
    }
    if (++nodes > maxNodes) {
      throw new JsonStructureLimitException(JsonStructureLimitException.NODES, maxNodes);
    }
  }

  private void checkDepth() {
    if (depth + 1 > maxDepth) {
      throw new JsonStructureLimitException(JsonStructureLimitException.DEPTH, maxDepth);
    }
  }

  private void push(boolean array) {
    if (depth == isArray.length) {
      isArray = Arrays.copyOf(isArray, depth * 2);
      elements = Arrays.copyOf(elements, depth * 2);
      expectName = Arrays.copyOf(expectName, depth * 2);
    }
    isArray[depth] = array;
    elements[depth] = 0;
    expectName[depth] = true;
    depth++;
    if (depth == 1) {
      rootIsObject = !array;
      rootMemberName = null;
    }
    if (array && arrayLimit != null && limitedDepth == 0 && isLimitedArray()) {
      limitedDepth = depth;
    }
  }

  private boolean isLimitedArray() {
    if (arrayLimit.member == null) {
      return depth == 1;
    }
    return depth == 2 && rootIsObject && arrayLimit.member.equals(rootMemberName);
  }

  private void pop() {
    if (depth == limitedDepth) {
      limitedDepth = 0;
    }
    depth--;
  }
}
