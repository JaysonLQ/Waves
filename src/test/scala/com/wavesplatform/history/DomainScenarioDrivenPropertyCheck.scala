package com.wavesplatform.history

import com.wavesplatform.db.WithState
import com.wavesplatform.settings.WavesSettings
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Assertion, Suite}

trait DomainScenarioDrivenPropertyCheck extends WithState { _: Suite with GeneratorDrivenPropertyChecks =>
  def scenario[S](gen: Gen[S], bs: WavesSettings = DefaultWavesSettings)(assertion: (Domain, S) => Assertion): Assertion =
    forAll(gen) { s =>
      withDomain(bs) { domain =>
        assertion(domain, s)
      }
    }
}
