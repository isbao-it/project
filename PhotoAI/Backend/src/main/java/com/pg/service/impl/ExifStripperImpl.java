package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.domain.WorkExif;
import com.pg.service.ExifStripper;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** 使用 JDK 解码、白名单提取及无元数据编码，GPS/序列号没有输出路径。 */
@Service
public class ExifStripperImpl implements ExifStripper {
    private static final int JPEG_PREFIX = 0xFF;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int APP1 = 0xE1;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int TIFF_HEADER_SIZE = 8;
    private static final int IFD_ENTRY_SIZE = 12;
    private static final int TIFF_MAGIC = 42;
    private static final int TAG_MODEL = 0x0110;
    private static final int TAG_ORIENTATION = 0x0112;
    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_EXPOSURE = 0x829A;
    private static final int TAG_APERTURE = 0x829D;
    private static final int TAG_ISO = 0x8827;
    private static final int TAG_FOCAL = 0x920A;
    private static final int TAG_LENS = 0xA434;
    private static final long MAX_PIXELS = 40_000_000L; // 控制解码内存，防止小体积压缩图耗尽服务堆。

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> strip(byte[] source) {
        try {
            Map<String, Object> exif = WorkExif.empty();
            String format;
            if (source.length >= 2 && unsigned(source[0]) == JPEG_PREFIX && unsigned(source[1]) == SOI) {
                format = "jpg";
                readJpeg(source, exif);
            } else if (source.length >= PNG_SIGNATURE.length
                    && Arrays.equals(Arrays.copyOf(source, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
                format = "png";
                readPng(source, exif);
            } else {
                throw invalid();
            }
            int orientation = ((Number) exif.getOrDefault("orientation", 1)).intValue();
            exif.remove("orientation");
            BufferedImage decoded = orient(decode(source), orientation);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            // 只编码像素，不复用 ImageReader 元数据；整个 EXIF/GPS/序列号段都不会被复制。
            if (!ImageIO.write(decoded, format, output)) {
                throw invalid();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bytes", output.toByteArray());
            result.put("format", format);
            result.put("exif", exif);
            return result;
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw invalid();
        }
    }

    private BufferedImage decode(byte[] source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw invalid();
                }
                // ImageIO 对部分截断文件只发警告；任何解码警告都按损坏图拒绝。
                reader.addIIOReadWarningListener((ignored, warning) -> { throw invalid(); });
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw invalid();
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) {
            return source;
        }
        // 手机常把旋转/镜像存在标签而不改像素；删标签前应用变换，否则安全图会横躺。
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage output = new BufferedImage(swap ? height : width, swap ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX = x;
                int targetY = y;
                switch (orientation) {
                    case 2: targetX = width - 1 - x; break;
                    case 3: targetX = width - 1 - x; targetY = height - 1 - y; break;
                    case 4: targetY = height - 1 - y; break;
                    case 5: targetX = y; targetY = x; break;
                    case 6: targetX = height - 1 - y; targetY = x; break;
                    case 7: targetX = height - 1 - y; targetY = width - 1 - x; break;
                    case 8: targetX = y; targetY = width - 1 - x; break;
                    default: throw invalid();
                }
                output.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return output;
    }

    private void readJpeg(byte[] source, Map<String, Object> exif) {
        int position = 2;
        boolean scan = false;
        while (position < source.length) {
            if (unsigned(source[position++]) != JPEG_PREFIX) {
                if (scan) {
                    continue;
                }
                throw invalid();
            }
            while (position < source.length && unsigned(source[position]) == JPEG_PREFIX) {
                position++;
            }
            require(position < source.length);
            int marker = unsigned(source[position++]);
            if (scan && (marker == 0 || (marker >= 0xD0 && marker <= 0xD7))) {
                continue;
            }
            if (marker == EOI) {
                require(scan);
                return; // JPEG 允许 EOI 后尾随填充，10MB 边界仍按原上传体积判断。
            }
            require(position + 2 <= source.length);
            int length = (unsigned(source[position]) << 8) | unsigned(source[position + 1]);
            require(length >= 2 && length <= source.length - position);
            if (marker == APP1 && length >= TIFF_HEADER_SIZE
                    && source[position + 2] == 'E' && source[position + 3] == 'x'
                    && source[position + 4] == 'i' && source[position + 5] == 'f'
                    && source[position + 6] == 0 && source[position + 7] == 0) {
                readTiff(Arrays.copyOfRange(source, position + 8, position + length), exif);
            }
            if (marker == SOS) {
                scan = true;
            }
            position += length;
        }
        throw invalid();
    }

    private void readPng(byte[] source, Map<String, Object> exif) {
        int position = PNG_SIGNATURE.length;
        while (position <= source.length - IFD_ENTRY_SIZE) {
            ByteBuffer chunk = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN);
            int length = chunk.getInt(position);
            require(length >= 0 && length <= source.length - position - IFD_ENTRY_SIZE);
            String type = new String(source, position + 4, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(source, position + 4, length + 4);
            require(crc.getValue() == Integer.toUnsignedLong(chunk.getInt(position + 8 + length)));
            if ("eXIf".equals(type)) {
                readTiff(Arrays.copyOfRange(source, position + 8, position + 8 + length), exif);
            }
            position += IFD_ENTRY_SIZE + length;
            if ("IEND".equals(type)) {
                require(length == 0 && position == source.length);
                return;
            }
        }
        throw invalid();
    }

    private void readTiff(byte[] bytes, Map<String, Object> exif) {
        require(bytes.length >= TIFF_HEADER_SIZE);
        boolean little = bytes[0] == 'I' && bytes[1] == 'I';
        require(little || (bytes[0] == 'M' && bytes[1] == 'M'));
        ByteBuffer tiff = ByteBuffer.wrap(bytes).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        require(Short.toUnsignedInt(tiff.getShort(2)) == TIFF_MAGIC);
        int first = offset(tiff, 4);
        int exifIfd = readIfd(tiff, first, exif, true);
        if (exifIfd != 0) {
            require(exifIfd != first);
            readIfd(tiff, exifIfd, exif, false);
        }
    }

    private int readIfd(ByteBuffer tiff, int start, Map<String, Object> exif, boolean root) {
        require(start >= TIFF_HEADER_SIZE && start <= tiff.capacity() - 2);
        int count = Short.toUnsignedInt(tiff.getShort(start));
        require((long) start + 2 + (long) count * IFD_ENTRY_SIZE + 4 <= tiff.capacity());
        int child = 0;
        for (int i = 0; i < count; i++) {
            int entry = start + 2 + i * IFD_ENTRY_SIZE;
            int tag = Short.toUnsignedInt(tiff.getShort(entry));
            if (tag == TAG_ORIENTATION && root) {
                int orientation = Short.toUnsignedInt(tiff.getShort(valueOffset(tiff, entry, 3, 2)));
                require(orientation >= 1 && orientation <= 8);
                exif.put("orientation", orientation);
            } else if (tag == TAG_EXIF_IFD && root) {
                require(Short.toUnsignedInt(tiff.getShort(entry + 2)) == 4 && tiff.getInt(entry + 4) == 1);
                child = offset(tiff, entry + 8);
            } else if (tag == TAG_MODEL || tag == TAG_LENS) {
                String value = ascii(tiff, entry);
                int length = value.codePointCount(0, value.length());
                if (length <= 64) {
                    exif.put(tag == TAG_MODEL ? "camera" : "lens", value);
                }
            } else if (tag == TAG_FOCAL || tag == TAG_APERTURE || tag == TAG_EXPOSURE) {
                int data = valueOffset(tiff, entry, 5, 8);
                long numerator = Integer.toUnsignedLong(tiff.getInt(data));
                long denominator = Integer.toUnsignedLong(tiff.getInt(data + 4));
                require(denominator > 0);
                BigDecimal value = BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
                if (tag == TAG_FOCAL && value.signum() > 0 && value.compareTo(BigDecimal.valueOf(32767)) <= 0
                        && value.scale() <= 0) {
                    exif.put("focal", value.intValueExact());
                } else if (tag == TAG_APERTURE && value.signum() > 0
                        && value.compareTo(new BigDecimal("99.9")) <= 0 && value.scale() <= 1) {
                    exif.put("aperture", value);
                } else if (tag == TAG_EXPOSURE && numerator > 0) {
                    long divisor = gcd(numerator, denominator);
                    String shutter = numerator / divisor + "/" + denominator / divisor;
                    if (shutter.length() <= 16) {
                        exif.put("shutter", shutter);
                    }
                }
            } else if (tag == TAG_ISO) {
                int data = valueOffset(tiff, entry, 3, 2);
                int iso = Short.toUnsignedInt(tiff.getShort(data));
                if (iso > 0 && iso <= 32767) {
                    exif.put("iso", iso);
                }
            }
            // GPS 与序列号等其它标签从不解析、不记录，也不放入返回值。
        }
        return child;
    }

    private String ascii(ByteBuffer tiff, int entry) {
        require(Short.toUnsignedInt(tiff.getShort(entry + 2)) == 2);
        long count = Integer.toUnsignedLong(tiff.getInt(entry + 4));
        require(count > 0 && count <= tiff.capacity());
        int data = count <= 4 ? entry + 8 : offset(tiff, entry + 8);
        require(count <= tiff.capacity() - data);
        int length = (int) count;
        while (length > 0 && tiff.get(data + length - 1) == 0) {
            length--;
        }
        return new String(tiff.array(), data, length, StandardCharsets.US_ASCII);
    }

    private int valueOffset(ByteBuffer tiff, int entry, int type, int width) {
        require(Short.toUnsignedInt(tiff.getShort(entry + 2)) == type && tiff.getInt(entry + 4) == 1);
        int data = width <= 4 ? entry + 8 : offset(tiff, entry + 8);
        require(data <= tiff.capacity() - width);
        return data;
    }

    private int offset(ByteBuffer tiff, int at) {
        long value = Integer.toUnsignedLong(tiff.getInt(at));
        require(value <= tiff.capacity());
        return (int) value;
    }

    private long gcd(long first, long second) {
        while (second != 0) {
            long rest = first % second;
            first = second;
            second = rest;
        }
        return first;
    }

    private int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_IMAGE, "图片解析或清洗失败");
    }
}
