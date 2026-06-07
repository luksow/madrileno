package madrileno.utils.featureflag.domain

import madrileno.utils.events.EventCodec
import madrileno.utils.events.EventCodec.given

enum FeatureFlagEvent derives EventCodec {
  case Invalidated(key: FlagKey) extends FeatureFlagEvent
}
