//! Wrapper around Rc<> to make ownership clearer
//!
//! The idea is to have ownership represented by a SharedBox<T>.
//! Temporary ownership can be held using a WeakBox<T>, which should
//! not be held across async points. This reduces the risk of accidental
//! lifetime extension.

use std::ops::Deref;
use std::rc::{Rc, Weak};

/// A Box<> where static "weak" references to the contents can be taken,
/// and fallibly upgraded at a later point. Unlike Rc<>, weak references
/// cannot be upgraded back to owning references, so ownership remains clear
/// and reference cycles avoided.
#[derive(Debug)]
pub struct SharedBox<T: ?Sized>(Rc<T>);

impl<T> SharedBox<T> {
    /// Constructor
    pub fn new(t: T) -> Self {
        Self(t.into())
    }

    /// Same as Rc::new_cyclic.
    pub fn new_cyclic(data_fn: impl FnOnce(WeakBox<T>) -> T) -> Self {
        Self(Rc::new_cyclic(|weak| data_fn(WeakBox(Weak::clone(weak)))))
    }

    /// Produce a weak reference to the contents
    pub fn downgrade(&self) -> WeakBox<T> {
        WeakBox(Rc::downgrade(&self.0))
    }
}

impl<T> From<T> for SharedBox<T> {
    fn from(value: T) -> Self {
        Self(value.into())
    }
}

impl<T> Deref for SharedBox<T> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        self.0.deref()
    }
}

/// A weak reference to the contents within a SharedBox<>
pub struct WeakBox<T: ?Sized>(Weak<T>);

impl<T: ?Sized> WeakBox<T> {
    /// Fallibly upgrade to a strong reference, passed into the supplied closure.  The strong
    /// reference is itself passed by reference into the closure to avoid accidental lifetime
    /// extension (by moving).
    ///
    /// Note: reference-counting is used so that, if the passed-in closure drops
    /// the SharedBox<>, the strong reference remains safe. But please don't
    /// do that!
    pub fn with<U>(&self, f: impl FnOnce(Option<&SharedBox<T>>) -> U) -> U {
        f(self.0.upgrade().map(|s| SharedBox(s)).as_ref())
    }
}

// NOTE: We don't use derived clone because that requires T: Clone.
impl<T: ?Sized> Clone for WeakBox<T> {
    fn clone(&self) -> Self {
        Self(self.0.clone())
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn test_shared_box() {
        let b = SharedBox::new(5);
        assert_eq!(*b, 5);

        let w = b.downgrade();
        w.with(|opt_b| {
            assert!(opt_b.is_some());
            assert_eq!(**opt_b.unwrap(), 5);
        });

        drop(b);
        w.with(|opt_b| {
            assert!(opt_b.is_none());
        });
    }

    #[test]
    fn test_new_cyclic() {
        struct Cycle {
            _f: RefCell<Option<WeakBox<Cycle>>>,
        }
        let b = SharedBox::new_cyclic(|w| Cycle { _f: RefCell::new(Some(w.clone())) });

        let w = b.downgrade();
        w.with(|opt_b| assert!(opt_b.is_some()));
        drop(b);
        w.with(|opt_b| assert!(opt_b.is_none()));
    }
}
