package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Test

/** The name a device joins with and shows as: what people call it, not a bare model number. */
class DeviceLabelTest {
    @Test fun settingsNameFirst() = assertEquals("Aaron's tablet", DeviceLabel.pick(" Aaron's tablet ", "onn", "100071485"))
    @Test fun settingsNameThatIsJustTheModelIsSkipped() = assertEquals("Onn 100071485", DeviceLabel.pick("100071485", "onn", "100071485"))
    @Test fun makerAndModel() = assertEquals("Google Pixel 9a", DeviceLabel.pick(null, "google", "Pixel 9a"))
    @Test fun makerNotTwice() = assertEquals("Samsung SM-S921U", DeviceLabel.pick("", "samsung", "Samsung SM-S921U"))
    @Test fun nothingKnown() = assertEquals("This device", DeviceLabel.pick(null, "", ""))
    @Test fun longNamesCut() = assertEquals(40, DeviceLabel.pick("x".repeat(60), "a", "b").length)

    @Test fun oldDefaultIsTheBareModel() {
        assertEquals(true, DeviceLabel.isOldDefault("100071485", "100071485"))
        assertEquals(true, DeviceLabel.isOldDefault("Phone", ""))
        assertEquals(false, DeviceLabel.isOldDefault("Onn tablet", "100071485"))
        assertEquals(false, DeviceLabel.isOldDefault(null, "100071485"))
    }
}
