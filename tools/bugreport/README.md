# Description

This tool provides functionalities to automatically extract and parse
BT Snoop logs from a Bluetooth bugreport.

# Usage

Select a specific analyzer using the corresponding subcommand.
Supported analyzers are listed below.

## A2DP

```
usage: bugreport.py a2dp [-h] [options] path

Extract A2DP profile information

positional arguments:
  path                  path to the bugreport file

options:
  -h, --help            show this help message and exit
  --signal-lcid SIGNAL_LCID
                        override the signaling channel LCID
  --signal-rcid SIGNAL_RCID
                        override the signaling channel RCID
  --stream-cid STREAM_CID
                        override the stream CID
  --codec-type CODEC_TYPE
                        override the codec type
  --sampling-frequency SAMPLING_FREQUENCY
                        override the sampling frequency
 ```

The A2DP analyzer will parse AVDTP signaling exchanges for each connection,
and automatically extract, plot and decode the audio stream when it is available
(i.e. not offloaded).

As a requirement, `ffmpeg` needs to be installed on the host machine.
`ldac` is not natively supported by ffmpeg, but [libldacdec](https://github.com/hegdi/libldacdec.git)
may be used to decode the extracted stream:

```
git clone https://github.com/hegdi/libldacdec.git
make -C libldacdec

cd libldacdec && ./ldacdec stream_LDAC_*.bt
```
