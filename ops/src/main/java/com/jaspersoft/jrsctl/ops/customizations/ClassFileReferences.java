package com.jaspersoft.jrsctl.ops.customizations;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The classes one class file names (issue #306, ADR-0061), read from its constant pool only: every
 * {@code CONSTANT_Class} entry, and every {@code L<name>;} in a {@code CONSTANT_Utf8} entry, which
 * is how field and method descriptors, generic signatures and annotation types name a class. No
 * class-file library is approved (spec §13.3), and the constant pool is all this check needs.
 * Invariants: reads the stream only up to {@code this_class}, never the fields, methods or code;
 * names are internal ({@code a/b/C}, {@code a/b/C$D} for a nested class), array markers stripped; a
 * stream that is not a class file is refused with {@link IOException}, never read as empty.
 */
record ClassFileReferences(String thisClass, int majorVersion, Set<String> referenced) {

  private static final int MAGIC = 0xCAFEBABE;
  private static final Pattern DESCRIPTOR = Pattern.compile("L([A-Za-z_$][\\w$]*(?:/[\\w$]+)+);");

  ClassFileReferences {
    referenced = Set.copyOf(referenced);
  }

  /** Reads {@code in}, which is left open and positioned somewhere inside the class file. */
  static ClassFileReferences read(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    if (data.readInt() != MAGIC) {
      throw new IOException("not a class file");
    }
    data.readUnsignedShort(); // minor
    int major = data.readUnsignedShort();
    int count = data.readUnsignedShort();
    String[] utf8 = new String[count];
    int[] classNameIndex = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = data.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = data.readUTF(); // u2 length + modified UTF-8, as DataInput reads it
        case 7 -> classNameIndex[i] = data.readUnsignedShort();
        case 8, 16, 19, 20 -> data.readUnsignedShort();
        case 3, 4, 9, 10, 11, 12, 17, 18 -> data.readInt();
        case 5, 6 -> {
          data.readLong();
          i++; // a long or a double takes two slots
        }
        case 15 -> {
          data.readUnsignedByte();
          data.readUnsignedShort();
        }
        default -> throw new IOException("unknown constant pool tag " + tag + " at entry " + i);
      }
    }
    data.readUnsignedShort(); // access flags
    int thisIndex = data.readUnsignedShort();
    Set<String> names = new TreeSet<>();
    String self = null;
    for (int i = 1; i < count; i++) {
      if (classNameIndex[i] > 0 && classNameIndex[i] < count && utf8[classNameIndex[i]] != null) {
        String name = stripArray(utf8[classNameIndex[i]]);
        if (i == thisIndex) {
          self = name;
        } else if (name != null) {
          names.add(name);
        }
      }
    }
    for (String s : utf8) {
      if (s != null && s.indexOf(';') > 0) {
        Matcher m = DESCRIPTOR.matcher(s);
        while (m.find()) {
          names.add(m.group(1));
        }
      }
    }
    if (self == null) {
      throw new IOException("class file names no class of its own");
    }
    names.remove(self);
    return new ClassFileReferences(self, major, names);
  }

  /** {@code [[Ljava/lang/String;} to {@code java/lang/String}; a primitive array to null. */
  private static String stripArray(String name) {
    if (!name.startsWith("[")) {
      return name;
    }
    Matcher m = DESCRIPTOR.matcher(name);
    return m.find() ? m.group(1) : null;
  }
}
