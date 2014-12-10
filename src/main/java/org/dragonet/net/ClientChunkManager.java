/*
 * GNU LESSER GENERAL PUBLIC LICENSE
 *                       Version 3, 29 June 2007
 *
 * Copyright (C) 2007 Free Software Foundation, Inc. <http://fsf.org/>
 * Everyone is permitted to copy and distribute verbatim copies
 * of this license document, but changing it is not allowed.
 *
 * You can view LICENCE file for details. 
 *
 * @author The Dragonet Team
 */
package org.dragonet.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.zip.Deflater;
import lombok.Getter;
import net.glowstone.GlowChunkSnapshot;
import net.glowstone.entity.GlowPlayer;
import org.apache.commons.lang.ArrayUtils;
import org.dragonet.ChunkLocation;
import org.dragonet.net.packet.minecraft.FullChunkPacket;
import org.dragonet.net.packet.minecraft.UnloadChunkPacket;
import org.dragonet.utilities.io.PEBinaryUtils;
import org.dragonet.utilities.io.PEBinaryWriter;

public class ClientChunkManager {

    private final @Getter
    DragonetSession session;

    private String lastWorld;

    private final ArrayList<ChunkLocation> chunksLoaded; //Already sent
    private final Deque<ChunkLocation> chunksQueue;  //Awaiting sending

    public ClientChunkManager(DragonetSession session) {
        this.session = session;
        this.chunksLoaded = new ArrayList<>();
        this.chunksQueue = new ArrayDeque<>();
    }

    /**
     * Trigger a chunk tick update
     */
    public void onTick() {
        if (this.getSession().getPlayer() == null) {
            return;
        }
        if (this.lastWorld == null) {
            this.lastWorld = this.getSession().getPlayer().getWorld().getName();
        }
        if (!this.lastWorld.equalsIgnoreCase(this.getSession().getPlayer().getWorld().getName())) {
            for(ChunkLocation loc : this.chunksLoaded){
                this.unloadChunk(loc.getX(), loc.getZ());
            }
            this.chunksLoaded.clear();
            this.chunksQueue.clear();
        }
        this.autoPrepareChunks();
        this.unloadFarChunks();
        this.sendChunks();
    }

    /**
     * Check whether a full chunk is loaded(prepared/sent)
     *
     * @param location Chunk location
     * @return The status, true=prepared/sent, false=not prepared/not sent
     */
    public boolean isChunkLoaded(ChunkLocation location) {
        for (ChunkLocation value : this.chunksLoaded) {
            if (value.equals(location)) {
                return true;
            }
        }
        for (ChunkLocation value : this.chunksQueue) {
            if (value.equals(location)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a full chunk is loaded(prepared/sent)
     *
     * @param x The chunk X coordinate
     * @param z The chunk Z coordinate
     * @return The status, true=prepared/sent, false=not prepared/not sent
     */
    public boolean isChunkLoaded(int x, int z) {
        return this.isChunkLoaded(new ChunkLocation(x, z));
    }

    /**
     * Add a chunk location to the chunk sending queue
     *
     * @param location Chunk location
     */
    public void prepareChunk(ChunkLocation location) {
        if (this.isChunkLoaded(location)) {
            return;
        }
        this.chunksQueue.add(location);
    }

    /**
     * Automatically prepare chunks
     */
    public void autoPrepareChunks() {
        if (!(this.getSession().getPlayer() instanceof GlowPlayer)) {
            return;
        }
        int chunkX = this.getSession().getPlayer().getLocation().getChunk().getX();
        int chunkZ = this.getSession().getPlayer().getLocation().getChunk().getZ();
        for (int distance = 7; distance >= 0; distance--) {
            for (int x = chunkX - distance; x < chunkX + distance; x++) {
                for (int z = chunkZ - distance; z < chunkZ + distance; z++) {
                    this.prepareChunk(new ChunkLocation(x, z));
                }
            }
        }
    }

    /**
     * Send all queued chunks to the client and mark them as sent
     */
    public synchronized void sendChunks() {
        if (!(this.getSession().getPlayer() instanceof GlowPlayer)) {
            return;
        }
        ChunkLocation chunkLocation;
        while ((chunkLocation = this.chunksQueue.poll()) != null) {
            this.sendChunk(chunkLocation.getX(), chunkLocation.getZ());
            this.chunksLoaded.add(chunkLocation);
        }
    }

    /**
     * Unload the chunks that distance > 8
     */
    public synchronized void unloadFarChunks() {
        if (!(this.getSession().getPlayer() instanceof GlowPlayer)) {
            return;
        }
        ChunkLocation playerChunk = new ChunkLocation(this.getSession().getPlayer().getLocation().getBlockX() / 16, this.getSession().getPlayer().getLocation().getBlockZ() / 16);
        ArrayList<ChunkLocation> toUnload = new ArrayList<>();
        for (ChunkLocation loc : this.chunksLoaded) {
            if (loc.distanceTo(playerChunk) > 16) {
                toUnload.add(loc);
                System.out.println("Chunk Distance " + playerChunk.toString() + " TO " + loc.toString() + " DISTANCE = " + loc.distanceTo(playerChunk));
            }
        }
        for (ChunkLocation locUnload : toUnload) {
            this.unloadChunk(locUnload.getX(), locUnload.getZ());
        }
        this.chunksLoaded.removeAll(toUnload);
    }

    /**
     * Send a single chunk to the client
     *
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     */
    private synchronized void sendChunk(int chunkX, int chunkZ) {
        try {
            if (!this.getSession().getPlayer().getWorld().getChunkAt(chunkX, chunkZ).isLoaded()) {
                this.getSession().getPlayer().getWorld().loadChunk(chunkX, chunkZ, true);
            }
            GlowChunkSnapshot chunk = this.getSession().getPlayer().getWorld().getChunkAt(chunkX, chunkZ).getChunkSnapshot();
            ByteArrayOutputStream totalData = new ByteArrayOutputStream();
            PEBinaryWriter writer = new PEBinaryWriter(totalData);
            if (writer.getEndianness() == PEBinaryUtils.BIG_ENDIAN) {
                writer.switchEndianness();
            }
            writer.writeInt(chunkX);
            writer.writeInt(chunkZ);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 128; y++) {
                        if (chunk.getBlockTypeId(x, y, z) != 0) {
                            writer.writeByte((byte) (this.getSession().getTranslator().getItemTranslator().translateToPE(chunk.getBlockTypeId(x, y, z)) & 0xFF));
                        } else {
                            writer.writeByte((byte) 0x00);
                        }
                    }
                }
            }
            writer.write(new byte[16384]);
            for (int i = 0; i < 16384; i++) {
                writer.writeByte((byte) 0xF0);
            }
            for (int i = 0; i < 16384; i++) {
                writer.writeByte((byte) 0x11);
            }
            for (int i = 0; i < 256; i++) {
                writer.writeByte((byte) 0x00);
            }
            for (int i = 0; i < 256; i++) {
                writer.writeByte((byte) 0x00);
                writer.writeByte((byte) 0x85);
                writer.writeByte((byte) 0xB2);
                writer.writeByte((byte) 0x4A);
            }
            Deflater deflater = new Deflater(2);
            deflater.reset();
            deflater.setInput(totalData.toByteArray());
            deflater.finish();
            byte[] bufferDeflate = new byte[65536];
            int deflatedSize = deflater.deflate(bufferDeflate);
            FullChunkPacket packet = new FullChunkPacket();
            packet.compressedData = ArrayUtils.subarray(bufferDeflate, 0, deflatedSize);
            this.getSession().send(packet);
        } catch (IOException e) {
        }
    }

    /**
     * Unload a chunk. THIS DOES NOT REMOVE FROM THE CHUNKSLOADED ARRAYLIST! 
     * @param x Chunk X position
     * @param z Chunk Z position
     */
    private void unloadChunk(int x, int z) {
        UnloadChunkPacket pkUnloadChunk = new UnloadChunkPacket();
        pkUnloadChunk.x = x;
        pkUnloadChunk.z = z;
        this.getSession().send(pkUnloadChunk);
    }
}
