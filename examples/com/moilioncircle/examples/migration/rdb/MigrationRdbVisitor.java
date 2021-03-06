/*
 * Copyright 2016-2018 Leon Chen
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

package com.moilioncircle.examples.migration.rdb;

import com.moilioncircle.redis.replicator.Replicator;
import com.moilioncircle.redis.replicator.event.Event;
import com.moilioncircle.redis.replicator.io.RawByteListener;
import com.moilioncircle.redis.replicator.io.RedisInputStream;
import com.moilioncircle.redis.replicator.rdb.BaseRdbParser;
import com.moilioncircle.redis.replicator.rdb.DefaultRdbVisitor;
import com.moilioncircle.redis.replicator.rdb.datatype.DB;
import com.moilioncircle.redis.replicator.rdb.datatype.Module;
import com.moilioncircle.redis.replicator.rdb.module.ModuleParser;
import com.moilioncircle.redis.replicator.rdb.skip.SkipRdbParser;
import com.moilioncircle.redis.replicator.util.ByteBuilder;

import java.io.IOException;
import java.util.NoSuchElementException;

import static com.moilioncircle.examples.util.CRC64.crc64;
import static com.moilioncircle.examples.util.CRC64.longToByteArray;
import static com.moilioncircle.redis.replicator.Constants.MODULE_SET;
import static com.moilioncircle.redis.replicator.Constants.RDB_MODULE_OPCODE_EOF;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_HASH;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_HASH_ZIPLIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_HASH_ZIPMAP;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_LIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_LIST_QUICKLIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_LIST_ZIPLIST;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_MODULE;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_MODULE_2;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_SET;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_SET_INTSET;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_STRING;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_ZSET;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_ZSET_2;
import static com.moilioncircle.redis.replicator.Constants.RDB_TYPE_ZSET_ZIPLIST;

/**
 * @author Leon Chen
 * @since 2.4.3
 */
public class MigrationRdbVisitor extends DefaultRdbVisitor {

    private class DefaultRawByteListener implements RawByteListener {
        private final int version;
        private final ByteBuilder builder;

        private DefaultRawByteListener(byte type, int version) {
            this.builder = ByteBuilder.allocate(MigrationRdbVisitor.this.size);
            this.builder.put(type);
            int ver = MigrationRdbVisitor.this.version;
            this.version = ver == -1 ? version : ver;
        }

        @Override
        public void handle(byte... rawBytes) {
            for (byte b : rawBytes) this.builder.put(b);
        }

        public byte[] getBytes() {
            this.builder.put((byte) version);
            this.builder.put((byte) 0x00);
            byte[] bytes = this.builder.array();
            byte[] crc = longToByteArray(crc64(bytes));
            for (byte b : crc) {
                this.builder.put(b);
            }
            return this.builder.array();
        }
    }

    private final int size;
    private final int version;

    public MigrationRdbVisitor(Replicator replicator) {
        this(replicator, -1, 8192);
    }

    public MigrationRdbVisitor(Replicator replicator, int version, int size) {
        super(replicator);
        this.version = version;
        this.size = size;
    }

    @Override
    public Event applyString(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o0 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_STRING, version);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadEncodedStringObject();
        replicator.removeRawByteListener(listener);
        o0.setValueRdbType(RDB_TYPE_STRING);
        o0.setValue(listener.getBytes());
        o0.setDb(db);
        o0.setRawKey(key);
        return o0;
    }

    @Override
    public Event applyList(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o1 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_LIST, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        long len = skipParser.rdbLoadLen().len;
        for (int i = 0; i < len; i++) {
            skipParser.rdbLoadEncodedStringObject();
        }
        replicator.removeRawByteListener(listener);
        o1.setValueRdbType(RDB_TYPE_LIST);
        o1.setValue(listener.getBytes());
        o1.setDb(db);
        o1.setRawKey(key);
        return o1;
    }

    @Override
    public Event applySet(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o2 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_SET, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        long len = skipParser.rdbLoadLen().len;
        for (int i = 0; i < len; i++) {
            skipParser.rdbLoadEncodedStringObject();
        }
        replicator.removeRawByteListener(listener);
        o2.setValueRdbType(RDB_TYPE_SET);
        o2.setValue(listener.getBytes());
        o2.setDb(db);
        o2.setRawKey(key);
        return o2;
    }

    @Override
    public Event applyZSet(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o3 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_ZSET, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        long len = skipParser.rdbLoadLen().len;
        while (len > 0) {
            skipParser.rdbLoadEncodedStringObject();
            skipParser.rdbLoadDoubleValue();
            len--;
        }
        replicator.removeRawByteListener(listener);
        o3.setValueRdbType(RDB_TYPE_ZSET);
        o3.setValue(listener.getBytes());
        o3.setDb(db);
        o3.setRawKey(key);
        return o3;
    }

    @Override
    public Event applyZSet2(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o5 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_ZSET_2, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        long len = skipParser.rdbLoadLen().len;
        while (len > 0) {
            skipParser.rdbLoadEncodedStringObject();
            skipParser.rdbLoadBinaryDoubleValue();
            len--;
        }
        replicator.removeRawByteListener(listener);
        o5.setValueRdbType(RDB_TYPE_ZSET_2);
        o5.setValue(listener.getBytes());
        o5.setDb(db);
        o5.setRawKey(key);
        return o5;
    }

    @Override
    public Event applyHash(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o4 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_HASH, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        long len = skipParser.rdbLoadLen().len;
        while (len > 0) {
            skipParser.rdbLoadEncodedStringObject();
            skipParser.rdbLoadEncodedStringObject();
            len--;
        }
        replicator.removeRawByteListener(listener);
        o4.setValueRdbType(RDB_TYPE_HASH);
        o4.setValue(listener.getBytes());
        o4.setDb(db);
        o4.setRawKey(key);
        return o4;
    }

    @Override
    public Event applyHashZipMap(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o9 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_HASH_ZIPMAP, version);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        o9.setValueRdbType(RDB_TYPE_HASH_ZIPMAP);
        o9.setValue(listener.getBytes());
        o9.setDb(db);
        o9.setRawKey(key);
        return o9;
    }

    @Override
    public Event applyListZipList(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o10 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_LIST_ZIPLIST, version);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        o10.setValueRdbType(RDB_TYPE_LIST_ZIPLIST);
        o10.setValue(listener.getBytes());
        o10.setDb(db);
        o10.setRawKey(key);
        return o10;
    }

    @Override
    public Event applySetIntSet(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o11 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_SET_INTSET, version);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        o11.setValueRdbType(RDB_TYPE_SET_INTSET);
        o11.setValue(listener.getBytes());
        o11.setDb(db);
        o11.setRawKey(key);
        return o11;
    }

    @Override
    public Event applyZSetZipList(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o12 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_ZSET_ZIPLIST, version);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        o12.setValueRdbType(RDB_TYPE_ZSET_ZIPLIST);
        o12.setValue(listener.getBytes());
        o12.setDb(db);
        o12.setRawKey(key);
        return o12;
    }

    @Override
    public Event applyHashZipList(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o13 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_HASH_ZIPLIST, version);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        o13.setValueRdbType(RDB_TYPE_HASH_ZIPLIST);
        o13.setValue(listener.getBytes());
        o13.setDb(db);
        o13.setRawKey(key);
        return o13;
    }

    @Override
    public Event applyListQuickList(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o14 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_LIST_QUICKLIST, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        long len = skipParser.rdbLoadLen().len;
        for (int i = 0; i < len; i++) {
            skipParser.rdbGenericLoadStringObject();
        }
        replicator.removeRawByteListener(listener);
        o14.setValueRdbType(RDB_TYPE_LIST_QUICKLIST);
        o14.setValue(listener.getBytes());
        o14.setDb(db);
        o14.setRawKey(key);
        return o14;
    }

    @Override
    public Event applyModule(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o6 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_MODULE, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        char[] c = new char[9];
        long moduleid = skipParser.rdbLoadLen().len;
        for (int i = 0; i < c.length; i++) {
            c[i] = MODULE_SET[(int) (moduleid >>> (10 + (c.length - 1 - i) * 6) & 63)];
        }
        String moduleName = new String(c);
        int moduleVersion = (int) (moduleid & 1023);
        ModuleParser<? extends Module> moduleParser = lookupModuleParser(moduleName, moduleVersion);
        if (moduleParser == null) {
            throw new NoSuchElementException("module[" + moduleName + "," + moduleVersion + "] not exist.");
        }
        moduleParser.parse(in, 1);
        replicator.removeRawByteListener(listener);
        o6.setValueRdbType(RDB_TYPE_MODULE);
        o6.setValue(listener.getBytes());
        o6.setDb(db);
        o6.setRawKey(key);
        return o6;
    }

    @Override
    public Event applyModule2(RedisInputStream in, DB db, int version) throws IOException {
        BaseRdbParser parser = new BaseRdbParser(in);
        MigrationKeyValuePair o7 = new MigrationKeyValuePair();
        byte[] key = parser.rdbLoadEncodedStringObject().first();
        DefaultRawByteListener listener = new DefaultRawByteListener((byte) RDB_TYPE_MODULE_2, version);
        replicator.addRawByteListener(listener);
        SkipRdbParser skipParser = new SkipRdbParser(in);
        char[] c = new char[9];
        long moduleid = skipParser.rdbLoadLen().len;
        for (int i = 0; i < c.length; i++) {
            c[i] = MODULE_SET[(int) (moduleid >>> (10 + (c.length - 1 - i) * 6) & 63)];
        }
        String moduleName = new String(c);
        int moduleVersion = (int) (moduleid & 1023);
        ModuleParser<? extends Module> moduleParser = lookupModuleParser(moduleName, moduleVersion);
        if (moduleParser == null) {
            throw new NoSuchElementException("module[" + moduleName + "," + moduleVersion + "] not exist.");
        }
        moduleParser.parse(in, 2);
        long eof = skipParser.rdbLoadLen().len;
        if (eof != RDB_MODULE_OPCODE_EOF) {
            throw new UnsupportedOperationException("The RDB file contains module data for the module '" + moduleName + "' that is not terminated by the proper module value EOF marker");
        }
        replicator.removeRawByteListener(listener);
        o7.setValueRdbType(RDB_TYPE_MODULE_2);
        o7.setValue(listener.getBytes());
        o7.setDb(db);
        o7.setRawKey(key);
        return o7;
    }

}
