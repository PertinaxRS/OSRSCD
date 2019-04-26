package com.pertinax.osrscd.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;


import static com.pertinax.osrscd.utils.BufferUtilities.getMediumInt;
import static com.pertinax.osrscd.utils.BufferUtilities.putMediumInt;

/**
 * Manages the reading and writing for a particular file store in the cache.
 *
 * @author Method
 */
public class FileStore {

	private static final int IDX_BLOCK_LEN = 6,
			HEADER_LEN = 8,
			EXPANDED_HEADER_LEN = 10,
			BLOCK_LEN = 512,
			EXPANDED_BLOCK_LEN = 510,
			TOTAL_BLOCK_LEN = HEADER_LEN + BLOCK_LEN;

	private static ByteBuffer tempBuffer = ByteBuffer.allocateDirect(TOTAL_BLOCK_LEN);

	private int index;
	private FileChannel indexChannel;
	private FileChannel dataChannel;
	private int maxSize;

	/**
	 * Creates a new FileStore object.
	 *
	 * @param index        The index of this file store.
	 * @param dataChannel  The channel of the data file for this file store.
	 * @param indexChannel The channel of the index file for this file store.
	 * @param maxSize      The maximum size of a file in this file store.
	 */
	public FileStore(int index, FileChannel dataChannel,
	                 FileChannel indexChannel, int maxSize) {
		this.index = index;
		this.dataChannel = dataChannel;
		this.indexChannel = indexChannel;
		this.maxSize = maxSize;
	}

	/**
	 * Gets the number of files stored in this file store.
	 *
	 * @return This file store's file count.
	 */
	public int getFileCount() {
		try {
			return (int) (indexChannel.size() / IDX_BLOCK_LEN);
		} catch (IOException ex) {
			return 0;
		}
	}

	/**
	 * Reads a file from the file store.
	 *
	 * @param file The file to read.
	 * @return The file's data, or null if the file was invalid.
	 */
	public ByteBuffer get(int file) {
		try {
			if (file * IDX_BLOCK_LEN + IDX_BLOCK_LEN > indexChannel.size()) {
				return null;
			}

			tempBuffer.position(0).limit(IDX_BLOCK_LEN);
			indexChannel.read(tempBuffer, file * IDX_BLOCK_LEN);
			tempBuffer.flip();
			int size = getMediumInt(tempBuffer);
			int block = getMediumInt(tempBuffer);

			if (size < 0 || size > maxSize || block <= 0 || block > dataChannel.size() / TOTAL_BLOCK_LEN) {
				return null;
			}

			ByteBuffer fileBuffer = ByteBuffer.allocate(size);
			int remaining = size;
			int chunk = 0;
			int blockLen = file <= 0xffff ? BLOCK_LEN : EXPANDED_BLOCK_LEN;
			int headerLen = file <= 0xffff ? HEADER_LEN : EXPANDED_HEADER_LEN;
			while (remaining > 0) {
				if (block == 0) {
					return null;
				}

				int blockSize = remaining > blockLen ? blockLen : remaining;
				tempBuffer.position(0).limit(blockSize + headerLen);
				dataChannel.read(tempBuffer, block * TOTAL_BLOCK_LEN);
				tempBuffer.flip();

				int currentFile, currentChunk, nextBlock, currentIndex;

				if (file <= 65535)
					currentFile = tempBuffer.getShort() & 0xffff;
				else
					currentFile = tempBuffer.getInt();

				currentChunk = tempBuffer.getShort() & 0xffff;
				nextBlock = getMediumInt(tempBuffer);
				currentIndex = tempBuffer.get() & 0xff;

				if (file != currentFile || chunk != currentChunk || index != currentIndex ||
						nextBlock < 0 || nextBlock > dataChannel.size() / TOTAL_BLOCK_LEN) {
					return null;
				}

				fileBuffer.put(tempBuffer);
				remaining -= blockSize;
				block = nextBlock;
				chunk++;
			}

			fileBuffer.flip();
			return fileBuffer;
		} catch (IOException ex) {
			return null;
		}
	}

	/**
	 * Writes an item to the file store.
	 *
	 * @param file The file to write.
	 * @param data The file's data.
	 * @param size The size of the file.
	 * @return true if the file was written, false otherwise.
	 */
	public boolean put(int file, ByteBuffer data, int size) {
		if (size < 0 || size > maxSize) {
			throw new IllegalArgumentException("File too big: " + file + " size: " + size);
		}
		boolean success = put(file, data, size, true);
		return !success ? put(file, data, size, false) : success;
	}

	private boolean put(int file, ByteBuffer data, int size, boolean exists) {
		try {
			int block;
			if (exists) {
				if (file * IDX_BLOCK_LEN + IDX_BLOCK_LEN > indexChannel.size()) {
					return false;
				}

				tempBuffer.position(0).limit(IDX_BLOCK_LEN);
				indexChannel.read(tempBuffer, file * IDX_BLOCK_LEN);
				tempBuffer.flip().position(3);
				block = getMediumInt(tempBuffer);

				if (block <= 0 || block > dataChannel.size() / TOTAL_BLOCK_LEN) {
					return false;
				}
			} else {
				block = (int) (dataChannel.size() + TOTAL_BLOCK_LEN - 1) / TOTAL_BLOCK_LEN;
				if (block == 0) {
					block = 1;
				}
			}

			tempBuffer.position(0);
			putMediumInt(tempBuffer, size);
			putMediumInt(tempBuffer, block);
			tempBuffer.flip();
			indexChannel.write(tempBuffer, file * IDX_BLOCK_LEN);

			int remaining = size;
			int chunk = 0;
			int blockLen = file <= 0xffff ? BLOCK_LEN : EXPANDED_BLOCK_LEN;
			int headerLen = file <= 0xffff ? HEADER_LEN : EXPANDED_HEADER_LEN;
			while (remaining > 0) {
				int nextBlock = 0;
				if (exists) {
					int currentFile, currentChunk, currentIndex;
					tempBuffer.position(0).limit(headerLen);
					dataChannel.read(tempBuffer, block * TOTAL_BLOCK_LEN);
					tempBuffer.flip();

					if (file <= 0xffff)
						currentFile = tempBuffer.getShort() & 0xffff;
					else
						currentFile = tempBuffer.getInt();

					currentChunk = tempBuffer.getShort() & 0xffff;
					nextBlock = getMediumInt(tempBuffer);
					currentIndex = tempBuffer.get() & 0xff;

					if (file != currentFile || chunk != currentChunk || index != currentIndex
							|| nextBlock < 0 || nextBlock > dataChannel.size() / TOTAL_BLOCK_LEN) {
						return false;
					}
				}

				if (nextBlock == 0) {
					exists = false;
					nextBlock = (int) ((dataChannel.size() + TOTAL_BLOCK_LEN - 1) / TOTAL_BLOCK_LEN);
					if (nextBlock == 0) {
						nextBlock = 1;
					}
					if (nextBlock == block) {
						nextBlock++;
					}
				}

				if (remaining <= blockLen) {
					nextBlock = 0;
				}
				tempBuffer.position(0).limit(TOTAL_BLOCK_LEN);
				if (file <= 0xffff)
					tempBuffer.putShort((short) file);
				else
					tempBuffer.putInt(file);
				tempBuffer.putShort((short) chunk);
				putMediumInt(tempBuffer, nextBlock);
				tempBuffer.put((byte) index);

				int blockSize = remaining > blockLen ? blockLen : remaining;
				data.limit(data.position() + blockSize);
				tempBuffer.put(data);
				tempBuffer.flip();

				dataChannel.write(tempBuffer, block * TOTAL_BLOCK_LEN);
				remaining -= blockSize;
				block = nextBlock;
				chunk++;
			}

			return true;
		} catch (IOException ex) {
			return false;
		}
	}
}
