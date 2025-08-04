/*
 * Copyright (C) 2019 The Turms Project
 * https://github.com/turms-im/turms
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.turms.server.common.infra.logging.core.compression;

import im.turms.server.common.infra.io.InputOutputException;
import im.turms.server.common.infra.memory.ByteBufferUtil;
import im.turms.server.common.infra.thread.NotThreadSafe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * This class has a better performance than {@link GZIPOutputStream} because the class: 1. uses
 * (direct) ByteBuffer instead of byte[] 2. caches and reuse data 3. no synchronized on our own
 * methods
 *
 * @author James Chen
 * @see java.util.zip.DeflaterOutputStream
 * @see java.util.zip.GZIPOutputStream
 */
@NotThreadSafe
public class FastGzipOutputStream implements AutoCloseable {

    private static final byte OS_UNKNOWN = (byte) 255;
    private static final int HEADER_MAGIC_NUMBER = 0x8b1f;
    private static final ByteBuffer HEADER =
            ByteBufferUtil.wrapAsDirect(new byte[]{(byte) HEADER_MAGIC_NUMBER, // Magic number
                                                                               // (short)
                    (byte) (HEADER_MAGIC_NUMBER >> 8), // Magic number (short)
                    Deflater.DEFLATED, // Compression method (CM)
                    0, // Flags (FLG)
                    0, // Modification time MTIME (int)
                    0, // Modification time MTIME (int)
                    0, // Modification time MTIME (int)
                    0, // Modification time MTIME (int)
                    0, // Extra flags (XFLG)
                    OS_UNKNOWN // Operating system (OS)
            });
    private static final int TRAILER_SIZE = 8;

    /**
     * GZIP 文件格式规范要求在文件的末尾包含一个 trailer (尾部)<br>
     * 其中含有原始未压缩数据的 CRC-32 校验和 + 数据的原始长度<br>
     * 这个校验和用于在解压时验证数据的完整性, 确保数据在传输或存储过程中没有被损坏
     */
    private final CRC32 crc = new CRC32();
    private final Deflater deflater;

    private FileChannel channel;
    private final ByteBuffer tempOut;
    private final ByteBuffer trailer = ByteBuffer.allocateDirect(TRAILER_SIZE);
    private boolean open;

    public FastGzipOutputStream(int compressionLevel, int tempOutputLength) {
        deflater = new Deflater(compressionLevel, true);
        tempOut = ByteBuffer.allocateDirect(tempOutputLength);
    }

    public void init(FileChannel channel) {
        if (open) {
            throw new IllegalStateException("Can only initialize after closed");
        }
        this.channel = channel;
        writeHeader();
        open = true;
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        Deflater localDeflater = deflater;
        ByteBuffer localTempOut = tempOut;
        FileChannel localChannel = channel;
        try {
            if (localDeflater.finished()) {
                writeTrailer();
            } else {
                localDeflater.finish();
                while (true) {
                    int written = localDeflater.deflate(localTempOut);
                    if (written > 0) {
                        localTempOut.flip();
                        localChannel.write(localTempOut);
                        localTempOut.clear();
                    }
                    if (localDeflater.finished()) {
                        writeTrailer();
                        break;
                    }
                }
            }
        } finally {
            crc.reset();
            localDeflater.reset();
            localTempOut.clear();
            open = false;
            localChannel.close();
        }
    }

    public void destroy() throws IOException {
        close();
        ByteBufferUtil.freeDirectBuffer(tempOut);
        ByteBufferUtil.freeDirectBuffer(trailer);
    }

    public void write(ByteBuffer input) throws IOException {
        Deflater localDeflater = deflater;
        localDeflater.setInput(input);     // 1. 设置压缩器的输入
        ByteBuffer localTempOut = tempOut; // 2. 缓冲区用于存储压缩后的数据
        while (input.hasRemaining()) {     // 3. 循环压缩和写入
            int written = localDeflater.deflate(localTempOut, Deflater.NO_FLUSH);
            if (written > 0) {
                localTempOut.flip();         // 1. 切换到读模式
                channel.write(localTempOut); // 2. 写入到通道
                localTempOut.clear();        // 3. 清空缓冲区，准备下一次写入
            }
        }
        input.clear();
        crc.update(input); // 4. 更新 CRC32 校验和
    }

    private void writeHeader() {
        try {
            channel.write(HEADER);
        } catch (IOException e) {
            throw new InputOutputException("Caught an error while writing the header", e);
        } finally {
            HEADER.clear();
        }
    }

    private void writeTrailer() throws IOException {
        try {
            trailer.order(ByteOrder.LITTLE_ENDIAN) // GZIP 文件格式要求使用小端字节序
                    .putInt((int) crc.getValue())  // CRC-32 校验和
                    .putInt(deflater.getTotalIn()) // 原始数据的长度
                    .flip();                       // 切换到读模式
            channel.write(trailer);
        } finally {
            trailer.clear();
        }
    }

}