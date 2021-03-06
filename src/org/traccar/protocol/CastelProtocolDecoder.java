/*
 * Copyright 2015 - 2016 Anton Tananaev (anton.tananaev@gmail.com)
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
package org.traccar.protocol;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.ObdDecoder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

public class CastelProtocolDecoder extends BaseProtocolDecoder {

    public CastelProtocolDecoder(CastelProtocol protocol) {
        super(protocol);
    }

    private static final short MSG_SC_LOGIN = 0x1001;
    private static final short MSG_SC_LOGIN_RESPONSE = (short) 0x9001;
    private static final short MSG_SC_LOGOUT = 0x1002;
    private static final short MSG_SC_HEARTBEAT = 0x1003;
    private static final short MSG_SC_HEARTBEAT_RESPONSE = (short) 0x9003;
    private static final short MSG_SC_GPS = 0x4001;
    private static final short MSG_SC_PID_DATA = 0x4002;
    private static final short MSG_SC_SUPPORTED_PID = 0x4004;
    private static final short MSG_SC_OBD_DATA = 0x4005;
    private static final short MSG_SC_DTCS_PASSENGER = 0x4006;
    private static final short MSG_SC_DTCS_COMMERCIAL = 0x400B;
    private static final short MSG_SC_ALARM = 0x4007;
    private static final short MSG_SC_CELL = 0x4008;
    private static final short MSG_SC_GPS_SLEEP = 0x4009;
    private static final short MSG_SC_AGPS_REQUEST = 0x5101;
    private static final short MSG_SC_CURRENT_LOCATION = (short) 0xB001;

    private static final short MSG_CC_LOGIN = 0x4001;
    private static final short MSG_CC_LOGIN_RESPONSE = (short) 0x8001;
    private static final short MSG_CC_HEARTBEAT = 0x4206;
    private static final short MSG_CC_HEARTBEAT_RESPONSE = (short) 0x8206;

    private Position readPosition(DeviceSession deviceSession, ChannelBuffer buf) {

        Position position = new Position();
        position.setProtocol(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        DateBuilder dateBuilder = new DateBuilder()
                .setDateReverse(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte())
                .setTime(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());
        position.setTime(dateBuilder.getDate());

        double lat = buf.readUnsignedInt() / 3600000.0;
        double lon = buf.readUnsignedInt() / 3600000.0;
        position.setSpeed(UnitsConverter.knotsFromCps(buf.readUnsignedShort()));
        position.setCourse(buf.readUnsignedShort() * 0.1);

        int flags = buf.readUnsignedByte();
        if ((flags & 0x02) == 0) {
            lat = -lat;
        }
        if ((flags & 0x01) == 0) {
            lon = -lon;
        }
        position.setLatitude(lat);
        position.setLongitude(lon);
        position.setValid((flags & 0x0C) > 0);
        position.set(Position.KEY_SATELLITES, flags >> 4);

        return position;
    }

    private void sendResponse(
            Channel channel, SocketAddress remoteAddress,
            int version, ChannelBuffer id, short type, ChannelBuffer content) {

        if (channel != null) {
            int length = 2 + 2 + 1 + id.readableBytes() + 2 + 2 + 2;
            if (content != null) {
                length += content.readableBytes();
            }

            ChannelBuffer response = ChannelBuffers.directBuffer(ByteOrder.LITTLE_ENDIAN, length);
            response.writeByte('@'); response.writeByte('@');
            response.writeShort(length);
            response.writeByte(version);
            response.writeBytes(id);
            response.writeShort(ChannelBuffers.swapShort(type));
            if (content != null) {
                response.writeBytes(content);
            }
            response.writeShort(
                    Checksum.crc16(Checksum.CRC16_X25, response.toByteBuffer(0, response.writerIndex())));
            response.writeByte(0x0D); response.writeByte(0x0A);
            channel.write(response, remoteAddress);
        }
    }

    private void sendResponse(
            Channel channel, SocketAddress remoteAddress, ChannelBuffer id, short type) {

        if (channel != null) {
            int length = 2 + 2 + id.readableBytes() + 2 + 4 + 8 + 2 + 2;

            ChannelBuffer response = ChannelBuffers.directBuffer(ByteOrder.LITTLE_ENDIAN, length);
            response.writeByte('@'); response.writeByte('@');
            response.writeShort(length);
            response.writeBytes(id);
            response.writeShort(ChannelBuffers.swapShort(type));
            response.writeInt(0);
            for (int i = 0; i < 8; i++) {
                response.writeByte(0xff);
            }
            response.writeShort(
                    Checksum.crc16(Checksum.CRC16_X25, response.toByteBuffer(0, response.writerIndex())));
            response.writeByte(0x0D); response.writeByte(0x0A);
            channel.write(response, remoteAddress);
        }
    }

    private Object decodeSc(
            Channel channel, SocketAddress remoteAddress, ChannelBuffer buf,
            int version, ChannelBuffer id, int type, DeviceSession deviceSession) {

        if (type == MSG_SC_HEARTBEAT) {

            sendResponse(channel, remoteAddress, version, id, MSG_SC_HEARTBEAT_RESPONSE, null);

        } else if (type == MSG_SC_LOGIN || type == MSG_SC_LOGOUT || type == MSG_SC_GPS
                || type == MSG_SC_ALARM || type == MSG_SC_CURRENT_LOCATION) {

            if (type == MSG_SC_LOGIN) {
                ChannelBuffer response = ChannelBuffers.directBuffer(ByteOrder.LITTLE_ENDIAN, 10);
                response.writeInt(0xFFFFFFFF);
                response.writeShort(0);
                response.writeInt((int) (System.currentTimeMillis() / 1000));
                sendResponse(channel, remoteAddress, version, id, MSG_SC_LOGIN_RESPONSE, response);
            }

            if (type == MSG_SC_GPS) {
                buf.readUnsignedByte(); // historical
            } else if (type == MSG_SC_ALARM) {
                buf.readUnsignedInt(); // alarm
            } else if (type == MSG_SC_CURRENT_LOCATION) {
                buf.readUnsignedShort();
            }

            buf.readUnsignedInt(); // ACC ON time
            buf.readUnsignedInt(); // UTC time
            long odometer = buf.readUnsignedInt();
            buf.readUnsignedInt(); // trip odometer
            buf.readUnsignedInt(); // total fuel consumption
            buf.readUnsignedShort(); // current fuel consumption
            long status = buf.readUnsignedInt();
            buf.skipBytes(8);

            int count = buf.readUnsignedByte();

            List<Position> positions = new LinkedList<>();

            for (int i = 0; i < count; i++) {
                Position position = readPosition(deviceSession, buf);
                position.set(Position.KEY_ODOMETER, odometer);
                position.set(Position.KEY_STATUS, status);
                positions.add(position);
            }

            if (!positions.isEmpty()) {
                return positions;
            }

        } else if (type == MSG_SC_GPS_SLEEP) {

            buf.readUnsignedInt(); // device time

            return readPosition(deviceSession, buf);

        } else if (type == MSG_SC_AGPS_REQUEST) {

            return readPosition(deviceSession, buf);

        } else if (type == MSG_SC_DTCS_PASSENGER) {

            Position position = new Position();
            position.setProtocol(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());

            getLastLocation(position, null);

            buf.skipBytes(6 * 4 + 2 + 8);

            buf.readUnsignedByte(); // flag
            position.add(ObdDecoder.decodeCodes(ChannelBuffers.hexDump(buf.readBytes(buf.readUnsignedByte()))));

            return position;

        } else if (type == MSG_SC_OBD_DATA) {

            Position position = new Position();
            position.setProtocol(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());

            getLastLocation(position, null);

            buf.skipBytes(6 * 4 + 2 + 8);

            // decode data

            return position;

        } else if (type == MSG_SC_CELL) {

            Position position = new Position();
            position.setProtocol(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());

            getLastLocation(position, null);

            buf.skipBytes(6 * 4 + 2 + 8);

            position.set(Position.KEY_LAC, buf.readUnsignedShort());
            position.set(Position.KEY_CID, buf.readUnsignedShort());

            return position;

        }

        return null;
    }


    private Object decodeCc(
            Channel channel, SocketAddress remoteAddress, ChannelBuffer buf,
            int version, ChannelBuffer id, int type, DeviceSession deviceSession) {

        if (type == MSG_CC_HEARTBEAT) {

            sendResponse(channel, remoteAddress, version, id, MSG_CC_HEARTBEAT_RESPONSE, null);

            buf.readUnsignedByte(); // 0x01 for history
            int count = buf.readUnsignedByte();

            List<Position> positions = new LinkedList<>();

            for (int i = 0; i < count; i++) {
                Position position = readPosition(deviceSession, buf);

                position.set(Position.KEY_STATUS, buf.readUnsignedInt());
                position.set(Position.KEY_BATTERY, buf.readUnsignedByte());
                position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());

                buf.readUnsignedByte(); // geo-fencing id
                buf.readUnsignedByte(); // geo-fencing flags
                buf.readUnsignedByte(); // additional flags

                position.set(Position.KEY_LAC, buf.readUnsignedShort());
                position.set(Position.KEY_CID, buf.readUnsignedShort());

                positions.add(position);
            }

            return positions;

        } else if (type == MSG_CC_LOGIN) {

            sendResponse(channel, remoteAddress, version, id, MSG_CC_LOGIN_RESPONSE, null);

            Position position = readPosition(deviceSession, buf);

            position.set(Position.KEY_STATUS, buf.readUnsignedInt());
            position.set(Position.KEY_BATTERY, buf.readUnsignedByte());
            position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());

            buf.readUnsignedByte(); // geo-fencing id
            buf.readUnsignedByte(); // geo-fencing flags
            buf.readUnsignedByte(); // additional flags

            // GSM_CELL_CODE
            // STR_Z - firmware version
            // STR_Z - hardware version

            return position;

        }

        return null;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        int header = buf.readUnsignedShort();
        buf.readUnsignedShort(); // length

        int version = -1;
        if (header == 0x4040) {
            version = buf.readUnsignedByte();
        }

        ChannelBuffer id = buf.readBytes(20);
        int type = ChannelBuffers.swapShort(buf.readShort());

        DeviceSession deviceSession = getDeviceSession(
                channel, remoteAddress, id.toString(StandardCharsets.US_ASCII).trim());
        if (deviceSession == null) {
            return null;
        }

        if (version == -1) {

            if (type == 0x2001) {

                sendResponse(channel, remoteAddress, id, (short) 0x1001);

                buf.readUnsignedInt(); // index
                buf.readUnsignedInt(); // unix time
                buf.readUnsignedByte();

                return readPosition(deviceSession, buf);

            }

        } else if (version == 4) {

            return decodeSc(channel, remoteAddress, buf, version, id, type, deviceSession);

        } else {

            return decodeCc(channel, remoteAddress, buf, version, id, type, deviceSession);

        }

        return null;
    }

}
