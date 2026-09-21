use lhdcv3_format::bitpack::BitWriter;
use lhdcv3_format::value::write_value;

/// The same code written in pieces small enough that the accumulator cannot
/// overflow, which is what the fast path has to agree with.
fn reference(w: &mut BitWriter, value: u32, shift: u32) {
    let q = value >> shift;
    assert!(q < 10);
    w.write(0, q);
    w.write(1, 1);
    w.write(value, shift);
}

#[test]
fn the_bits_are_the_same_whatever_is_already_pending() {
    for pending in 0..8u32 {
        for &(v, shift) in &[(627740u32, 16u32), (430036, 16), (162441, 16), (65535, 16)] {
            let mut a = BitWriter::new();
            a.write(0, pending);
            write_value(&mut a, v, shift);
            let mut b = BitWriter::new();
            b.write(0, pending);
            reference(&mut b, v, shift);
            assert_eq!(
                a.finish(),
                b.finish(),
                "value={v} shift={shift} pending={pending}"
            );
        }
    }
}
