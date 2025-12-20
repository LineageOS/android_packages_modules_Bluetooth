pub struct PayloadAccumulator<T: pdl_runtime::Packet> {
    curr: usize,
    lim: usize,
    elems: Vec<T>,
}

impl<T: pdl_runtime::Packet> PayloadAccumulator<T> {
    pub fn new(size: usize) -> Self {
        Self { curr: 0, lim: size, elems: vec![] }
    }

    #[must_use]
    pub fn push(&mut self, builder: T) -> bool {
        // if serialization fails we WANT to continue, to get a clean SerializeError at
        // the end
        let elem_size = builder.encoded_len();
        if elem_size + self.curr > self.lim {
            return false;
        }
        self.elems.push(builder);
        self.curr += elem_size;
        true
    }

    pub fn into_vec(self) -> Vec<T> {
        self.elems
    }

    pub fn is_empty(&self) -> bool {
        self.elems.is_empty()
    }
}

#[cfg(test)]
mod test {
    use crate::packets::att;

    use super::PayloadAccumulator;

    #[test]
    fn test_empty() {
        let accumulator = PayloadAccumulator::<att::Att>::new(0);
        assert!(accumulator.is_empty())
    }
    #[test]
    fn test_nonempty() {
        let mut accumulator = PayloadAccumulator::new(128);

        let ok = accumulator
            .push(att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![1, 2] });

        assert!(ok);
        assert!(!accumulator.is_empty())
    }

    #[test]
    fn test_push_serialize() {
        let mut accumulator = PayloadAccumulator::new(128);

        let ok = accumulator
            .push(att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![1, 2] });

        assert!(ok);
        assert_eq!(
            accumulator.into_vec().as_ref(),
            [att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![1, 2] }]
        );
    }

    #[test]
    fn test_push_past_capacity() {
        let mut accumulator = PayloadAccumulator::new(5);

        // each builder is 3 bytes, so the first should succeed, the second should fail
        let first_ok = accumulator
            .push(att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![1, 2] });
        let second_ok = accumulator
            .push(att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![3, 4] });

        // assert: the first one is pushed and is correctly output, but the second is
        // dropped
        assert!(first_ok);
        assert!(!second_ok);
        assert_eq!(
            accumulator.into_vec().as_ref(),
            [att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![1, 2] }]
        );
    }

    #[test]
    fn test_push_to_capacity() {
        let mut accumulator = PayloadAccumulator::new(5);

        // 3 + 2 bytes = the size, so both should push correctly
        let first_ok = accumulator
            .push(att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![1, 2] });
        let second_ok =
            accumulator.push(att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![3] });

        // assert: both are pushed and output correctly
        assert!(first_ok);
        assert!(second_ok);
        assert_eq!(
            accumulator.into_vec().as_ref(),
            [
                att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![1, 2] },
                att::Att { opcode: att::AttOpcode::WriteResponse, payload: vec![3] }
            ]
        );
    }
}
