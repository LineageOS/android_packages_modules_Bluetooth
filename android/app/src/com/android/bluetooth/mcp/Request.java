/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com.
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

package com.android.bluetooth.mcp;

import static java.util.Map.entry;

import java.util.Map;

/**
 * Media control request, from client to Media Player
 *
 * @param opcode Control request opcode
 * @param arg Control request argument
 */
record Request(int opcode, int arg) {

    /** Media control request results definition */
    public enum Results {
        SUCCESS(0x01),
        OPCODE_NOT_SUPPORTED(0x02),
        MEDIA_PLAYER_INACTIVE(0x03),
        COMMAND_CANNOT_BE_COMPLETED(0x04);

        private final int mValue;

        Results(int value) {
            mValue = value;
        }

        public int getValue() {
            return mValue;
        }
    }

    /** Media control request supported opcodes definition */
    public static final class SupportedOpcodes {
        public static final int NONE = 0x00;
        public static final int PLAY = 0x01;
        public static final int PAUSE = 0x02;
        public static final int FAST_REWIND = 0x04;
        public static final int FAST_FORWARD = 0x08;
        public static final int STOP = 0x10;
        public static final int MOVE_RELATIVE = 0x20;
        public static final int PREVIOUS_SEGMENT = 0x40;
        public static final int NEXT_SEGMENT = 0x80;
        public static final int FIRST_SEGMENT = 0x0100;
        public static final int LAST_SEGMENT = 0x0200;
        public static final int GOTO_SEGMENT = 0x0400;
        public static final int PREVIOUS_TRACK = 0x0800;
        public static final int NEXT_TRACK = 0x1000;
        public static final int FIRST_TRACK = 0x2000;
        public static final int LAST_TRACK = 0x4000;
        public static final int GOTO_TRACK = 0x8000;
        public static final int PREVIOUS_GROUP = 0x010000;
        public static final int NEXT_GROUP = 0x020000;
        public static final int FIRST_GROUP = 0x040000;
        public static final int LAST_GROUP = 0x080000;
        public static final int GOTO_GROUP = 0x100000;

        static String toString(int opcodes) {
            StringBuilder sb = new StringBuilder();
            boolean is_complex = false;

            sb.append("0x").append(Integer.toHexString(opcodes)).append(" (");
            for (int i = 1; i <= opcodes; i <<= 1) {
                int opcode = opcodes & i;
                if (opcode != 0) {
                    if (is_complex) {
                        sb.append(" | ");
                    } else {
                        is_complex = true;
                    }
                    sb.append(singleOpcodeToString(opcode));
                }
            }
            sb.append(")");
            return sb.toString();
        }

        private static String singleOpcodeToString(int opcode) {
            return switch (opcode) {
                case 0x01 -> "PLAY";
                case 0x02 -> "PAUSE";
                case 0x04 -> "FAST_REWIND";
                case 0x08 -> "FAST_FORWARD";
                case 0x10 -> "STOP";
                case 0x20 -> "MOVE_RELATIVE";
                case 0x40 -> "PREVIOUS_SEGMENT";
                case 0x80 -> "NEXT_SEGMENT";
                case 0x0100 -> "FIRST_SEGMENT";
                case 0x0200 -> "LAST_SEGMENT";
                case 0x0400 -> "GOTO_SEGMENT";
                case 0x0800 -> "PREVIOUS_TRACK";
                case 0x1000 -> "NEXT_TRACK";
                case 0x2000 -> "FIRST_TRACK";
                case 0x4000 -> "LAST_TRACK";
                case 0x8000 -> "GOTO_TRACK";
                case 0x010000 -> "PREVIOUS_GROUP";
                case 0x020000 -> "NEXT_GROUP";
                case 0x040000 -> "FIRST_GROUP";
                case 0x080000 -> "LAST_GROUP";
                case 0x100000 -> "GOTO_GROUP";
                default -> "0x" + Integer.toHexString(opcode);
            };
        }
    }

    /** Media control request opcodes definition */
    public static final class Opcodes {
        public static final int PLAY = 0x01;
        public static final int PAUSE = 0x02;
        public static final int FAST_REWIND = 0x03;
        public static final int FAST_FORWARD = 0x04;
        public static final int STOP = 0x05;
        public static final int MOVE_RELATIVE = 0x10;
        public static final int PREVIOUS_SEGMENT = 0x20;
        public static final int NEXT_SEGMENT = 0x21;
        public static final int FIRST_SEGMENT = 0x22;
        public static final int LAST_SEGMENT = 0x23;
        public static final int GOTO_SEGMENT = 0x24;
        public static final int PREVIOUS_TRACK = 0x30;
        public static final int NEXT_TRACK = 0x31;
        public static final int FIRST_TRACK = 0x32;
        public static final int LAST_TRACK = 0x33;
        public static final int GOTO_TRACK = 0x34;
        public static final int PREVIOUS_GROUP = 0x40;
        public static final int NEXT_GROUP = 0x41;
        public static final int FIRST_GROUP = 0x42;
        public static final int LAST_GROUP = 0x43;
        public static final int GOTO_GROUP = 0x44;

        static String toString(int opcode) {
            return switch (opcode) {
                case 0x01 -> "PLAY(0x01)";
                case 0x02 -> "PAUSE(0x02)";
                case 0x03 -> "FAST_REWIND(0x03)";
                case 0x04 -> "FAST_FORWARD(0x04)";
                case 0x05 -> "STOP(0x05)";
                case 0x10 -> "MOVE_RELATIVE(0x10)";
                case 0x20 -> "PREVIOUS_SEGMENT(0x20)";
                case 0x21 -> "NEXT_SEGMENT(0x21)";
                case 0x22 -> "FIRST_SEGMENT(0x22)";
                case 0x23 -> "LAST_SEGMENT(0x23)";
                case 0x24 -> "GOTO_SEGMENT(0x24)";
                case 0x30 -> "PREVIOUS_TRACK(0x30)";
                case 0x31 -> "NEXT_TRACK(0x31)";
                case 0x32 -> "FIRST_TRACK(0x32)";
                case 0x33 -> "LAST_TRACK(0x33)";
                case 0x34 -> "GOTO_TRACK(0x34)";
                case 0x40 -> "PREVIOUS_GROUP(0x40)";
                case 0x41 -> "NEXT_GROUP(0x41)";
                case 0x42 -> "FIRST_GROUP(0x42)";
                case 0x43 -> "LAST_GROUP(0x43)";
                case 0x44 -> "GOTO_GROUP(0x44)";
                default -> "UNKNOWN(0x" + Integer.toHexString(opcode) + ")";
            };
        }
    }

    /* Map opcodes which are written to 'Media Control Point' characteristics to their corresponding
     * feature bit masks used in 'Media Control Point Opcodes Supported' characteristic.
     */
    public static final Map<Integer, Integer> OpcodeToOpcodeSupport =
            Map.ofEntries(
                    entry(Opcodes.PLAY, SupportedOpcodes.PLAY),
                    entry(Opcodes.PAUSE, SupportedOpcodes.PAUSE),
                    entry(Opcodes.FAST_REWIND, SupportedOpcodes.FAST_REWIND),
                    entry(Opcodes.FAST_FORWARD, SupportedOpcodes.FAST_FORWARD),
                    entry(Opcodes.STOP, SupportedOpcodes.STOP),
                    entry(Opcodes.MOVE_RELATIVE, SupportedOpcodes.MOVE_RELATIVE),
                    entry(Opcodes.PREVIOUS_SEGMENT, SupportedOpcodes.PREVIOUS_SEGMENT),
                    entry(Opcodes.NEXT_SEGMENT, SupportedOpcodes.NEXT_SEGMENT),
                    entry(Opcodes.FIRST_SEGMENT, SupportedOpcodes.FIRST_SEGMENT),
                    entry(Opcodes.LAST_SEGMENT, SupportedOpcodes.LAST_SEGMENT),
                    entry(Opcodes.GOTO_SEGMENT, SupportedOpcodes.GOTO_SEGMENT),
                    entry(Opcodes.PREVIOUS_TRACK, SupportedOpcodes.PREVIOUS_TRACK),
                    entry(Opcodes.NEXT_TRACK, SupportedOpcodes.NEXT_TRACK),
                    entry(Opcodes.FIRST_TRACK, SupportedOpcodes.FIRST_TRACK),
                    entry(Opcodes.LAST_TRACK, SupportedOpcodes.LAST_TRACK),
                    entry(Opcodes.GOTO_TRACK, SupportedOpcodes.GOTO_TRACK),
                    entry(Opcodes.PREVIOUS_GROUP, SupportedOpcodes.PREVIOUS_GROUP),
                    entry(Opcodes.NEXT_GROUP, SupportedOpcodes.NEXT_GROUP),
                    entry(Opcodes.FIRST_GROUP, SupportedOpcodes.FIRST_GROUP),
                    entry(Opcodes.LAST_GROUP, SupportedOpcodes.LAST_GROUP),
                    entry(Opcodes.GOTO_GROUP, SupportedOpcodes.GOTO_GROUP));
}
