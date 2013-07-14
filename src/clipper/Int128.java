package clipper;

import java.math.BigInteger;

public class Int128 {
	private BigInteger value;

	public Int128(long value) {
		this.value = BigInteger.valueOf(value);
	}

	Int128(BigInteger value) {
		this.value = value;
	}

	public Int128 add(long x) {
		return new Int128(value.add(BigInteger.valueOf(x)));
	}

	public Int128 add(Int128 other) {
		return new Int128(value.add(other.value));
	}

	public Int128 divide(Int128 other) {
		return new Int128(value.divide(other.value));
	}

	public long getValue() {
		return value.longValue();
	}

	public double toDouble() {
		return value.doubleValue();
	}

	public static Int128 multiply(long a, long b) {
		return new Int128(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)));
	}

	public int compareTo(Int128 other) {
		return value.compareTo(other.value);
	}
}