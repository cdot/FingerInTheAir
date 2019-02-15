package com.cdot.fingerintheair;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.cdot.onewire.OneWireError;
import com.cdot.onewire.OneWireDriver;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

/**
 * 1-wire serial port interface using the UsbSerial android usb serial port library
 */
class AndroidSerial1WireDriver extends OneWireDriver {

    private UsbSerialDevice serialPort;

    AndroidSerial1WireDriver(UsbDevice device, UsbDeviceConnection connection) {
        serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
        serialPort.syncOpen();
     }

     public boolean isUsingPort(String port) {
        return serialPort.getPortName() == port;
     }

     public OneWireError reset() {
        //logger.log("touchReset");
        serialPort.setBaudRate(9600);
        serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
        serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
        serialPort.setParity(UsbSerialInterface.PARITY_NONE);
        serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

        // Send the 480ms registration pulse
        byte[] buffer = {(byte) 0xF0};
        serialPort.syncWrite(buffer, 0);

        // Look for presence pulse
        int nRead = serialPort.syncRead(buffer, 0);
        if (nRead != 1) {
            //logger.log("reset failed, nRead " + nRead);
            return OneWireError.RESET_FAILED;
        }

        int result = buffer[0];

        if (result == 0) {
            // Data line is a short to ground
            //logger.log("reset failed, short to ground");
            return OneWireError.RESET_FAILED;
        }

        if (result == 0xF0) {
            // No device responding
            //logger.log("reset failed, nothing on net ");
            return OneWireError.NO_DEVICES_ON_NET;
        }

        serialPort.setBaudRate(115200);

        //logger.log(String.format("/touchReset got %02X", (byte)result));
        return OneWireError.NO_ERROR_SET;
    }

    public boolean touchBit(boolean sbit) {
        byte[] buf = new byte[1];
        buf[0] = (byte) (sbit ? 0xFF : 0);
        serialPort.syncWrite(buf, 0);
        //logger.log("touchBit: wrote "+buf.length);

        int nRead = serialPort.syncRead(buf, 0);
        if (nRead != 1) {
            //logger.log("touchBit problem: read "+nRead);
            throw new Error("Problem: read " + nRead);
        }
        //System.out.println("/TouchBit: send: " + hex(tx[0]) + ", receive: " + hex(buffer[0]));
        return ((buf[0] & 1) != 0);
    }

    /**
     * Transfers a block of data to and from the 1-Wire Net
     *
     * @param tx pointer to a block of bytes that will be sent
     * @return the response, always the same length as tx (or
     * null if there was an error)
     */
    public byte[] touchBlock(byte[] tx) {

        if (tx.length > OneWireDriver.UART_FIFO_SIZE) {
            last_error = OneWireError.BLOCK_TOO_BIG;
            return null;
        }

        // send and receive the buffer
        byte[] rx = new byte[tx.length];
        for (int i = 0; i < tx.length; i++) {
            rx[i] = touchByte(tx[i]);
        }
        return rx;
    }

    public byte touchByte(byte txbyte) {
        byte rxbyte = 0;

        // Construct string of bytes representing bits to be sent
        byte[] buf = new byte[8];
        for (int i = 0; i < 8; i++) {
            // Bits are taken from the [0] byte first
            // Bits are taken from each byte lsb first
            buf[i] = (byte) ((txbyte & (1 << (i & 0x7))) != 0 ? 0xFF : 0x00);
        }

        //logger.log(String.format("touchByte: wrote %02X", txbyte));

        serialPort.syncWrite(buf, 0);

        int nRead = 8;
        while (nRead > 0) {
            int nr = serialPort.syncRead(buf, 500);
            if (nr == 0)
                throw new Error("touchByte problem: timeout");
            nRead -= nr;
            for (int i = 0; i < nr; i++) {
                //logger.log(String.format("touchByte: %d of %d=%02X", i, nr, buf[i]));
                rxbyte >>= 1;
                if ((buf[i] & 0x01) != 0)
                    rxbyte |= 0x80;
                else
                    rxbyte &= 0x7F;

            }
            //logger.log("touchByte: read "+nr+" waiting for "+nRead);
        }
        //logger.log(String.format("touchByte: result = %02X", rxbyte));

        return rxbyte;
    }

    /**
     * Delay for at least 'len' ms
     */
    public void msDelay(int len) {
        try {
            Thread.sleep(len, 0);
        } catch (InterruptedException ie) {
        }
    }
}
