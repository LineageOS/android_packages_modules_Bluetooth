//! This library provides Chrome feature query service.

#[cfg(feature = "chromeos")]
use featured::CheckFeature;

/// Queries whether the specified Chrome feature is enabled.
/// Returns false when the build is not for ChromeOS.
#[allow(unused_variables)]
pub fn is_feature_enabled(name: &str) -> Result<bool, Box<dyn std::error::Error>> {
    cfg_if::cfg_if! {
        if #[cfg(feature = "chromeos")] {
            let feature = featured::Feature::new(&name, false)?;

            let resp = featured::PlatformFeatures::get()?
                .is_feature_enabled_blocking(&feature);

            Ok(resp)
        } else {
            Ok(false)
        }
    }
}
