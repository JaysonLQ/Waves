package com.wavesplatform.it.sync.smartcontract

import com.wavesplatform.account.{PrivateKeyAccount}
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.state._
import com.wavesplatform.transaction.assets.exchange._
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.utils.NTP
import org.scalatest.CancelAfterFailure
import com.wavesplatform.it.util._
import com.wavesplatform.transaction.{DataTransaction}
import play.api.libs.json._
import scorex.crypto.encode.Base64

class ExchangeTransactionSuite extends BaseTransactionSuite with CancelAfterFailure {
  private val acc0 = pkByAddress(firstAddress)
  private val acc1 = pkByAddress(secondAddress)
  private val acc2 = pkByAddress(thirdAddress)

  var exchAsset: String    = null
  var dtx: DataTransaction = null

  val sc1 = s"""true"""
  val sc2 = s"""
               |match tx {
               |  case s : SetScriptTransaction => true
               |  case _ => false
               |}""".stripMargin
  val sc3 = s"""
               |match tx {
               |  case s : SetScriptTransaction => true
               |  case _ => throw("Some generic error")
               |}""".stripMargin

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    exchAsset = sender
      .issue(acc0.address, "ExchangeCoin", "ExchangeCoin for tests with exchange transaction", someAssetAmount, 0, reissuable = false, issueFee, 2)
      .id
    nodes.waitForHeightAriseAndTxPresent(exchAsset)

    val entry1 = IntegerDataEntry("int", 24)
    val entry2 = BooleanDataEntry("bool", true)
    val entry3 = BinaryDataEntry("blob", ByteStr(Base64.decode("YWxpY2U=")))
    val entry4 = StringDataEntry("str", "test")

    dtx = DataTransaction.selfSigned(1, acc0, List(entry1, entry2, entry3, entry4), 0.001.waves, NTP.correctedTime()).explicitGet()
    val dtxId = sender.signedBroadcast(dtx.json()).id
    nodes.waitForHeightAriseAndTxPresent(dtxId)
  }

  test("set contracts and put exchange transaction in blockchain") {
    val sc4 = cryptoContext(dtx)
    val sc5 = pureContext(dtx)
    val sc6 = wavesContext(dtx)

    for ((contr1, contr2, mcontr) <- Seq(
           (sc1, sc1, sc1),
           (null, sc1, null),
           (null, null, sc1),
           (null, null, sc4),
           (null, null, sc5),
           (null, null, sc6)
         )) {
      setContract(contr1, acc0)
      setContract(contr2, acc1)
      setContract(mcontr, acc2)

      nodes.waitForHeightArise()

      val tx = exchangeTx()

      val txId = sender.signedBroadcast(tx).id
      nodes.waitForHeightAriseAndTxPresent(txId)
      //TODO : add assert balances
    }
    setContract(null, acc0)
    setContract(null, acc1)
    setContract(null, acc2)
  }

  test("negative: set simple contracts and put exchange transaction in blockchain") {
    for ((contr1, contr2, mcontr) <- Seq(
           (sc1, sc2, sc1),
           (sc1, sc1, sc2),
           (null, null, sc2),
           (null, sc2, null)
         )) {
      setContract(contr1, acc0)
      setContract(contr2, acc1)
      setContract(mcontr, acc2)

      val tx = exchangeTx()
      assertBadRequestAndMessage(sender.signedBroadcast(tx), "Transaction not allowed by account-script")
      //TODO : add assert balances
    }
    setContract(null, acc0)
    setContract(null, acc1)
    setContract(null, acc2)
  }

  test("negative: check custom exception") {
    for ((contr1, contr2, mcontr) <- Seq(
           (sc1, sc1, sc3)
         )) {
      setContract(contr1, acc0)
      setContract(contr2, acc1)
      setContract(mcontr, acc2)

      val tx = exchangeTx()
      assertBadRequestAndMessage(sender.signedBroadcast(tx), "Error while executing account-script: Some generic error")
      //TODO : add assert balances
    }
    setContract(null, acc0)
    setContract(null, acc1)
    setContract(null, acc2)
  }

  test("positive: versioning verification") {
    for ((contr1, contr2, mcontr) <- Seq(
           (null, null, null),
           (sc1, null, null),
           (null, null, sc1)
         )) {
      setContract(contr1, acc0)
      setContract(contr2, acc1)
      setContract(mcontr, acc2)

      val mf        = 700000L
      val matcher   = acc2
      val sellPrice = (0.50 * Order.PriceConstant).toLong
      val buy       = orders(2)._1
      val sell      = orders(1)._2

      val amount = math.min(buy.amount, sell.amount)
      val tx = ExchangeTransactionV2
        .create(
          matcher = matcher,
          buyOrder = buy,
          sellOrder = sell,
          amount = amount,
          price = sellPrice,
          buyMatcherFee = (BigInt(mf) * amount / buy.amount).toLong,
          sellMatcherFee = (BigInt(mf) * amount / sell.amount).toLong,
          fee = mf,
          timestamp = NTP.correctedTime()
        )
        .explicitGet()
        .json()

      val txId = sender.signedBroadcast(tx).id
      nodes.waitForHeightAriseAndTxPresent(txId)

      //TODO : add assert balances
    }
    setContract(null, acc0)
    setContract(null, acc1)
    setContract(null, acc2)
  }

  test("negative: check orders v2 with exchange tx v1") {
    val tx        = exchangeTx()
    val sig       = (Json.parse(tx.toString()) \ "proofs").as[Seq[JsString]].head
    val changedTx = tx + ("version" -> JsNumber(1)) + ("signature" -> sig)
    println(changedTx)
    assertBadRequest(sender.signedBroadcast(changedTx).id, 500) //TODO: change to correct error message
  }

  def exchangeTx() = {
    val mf        = 700000L
    val matcher   = acc2
    val sellPrice = (0.50 * Order.PriceConstant).toLong
    val buy       = orders()._1
    val sell      = orders()._2

    val amount = math.min(buy.amount, sell.amount)
    val tx = ExchangeTransactionV2
      .create(
        matcher = matcher,
        buyOrder = buy,
        sellOrder = sell,
        amount = amount,
        price = sellPrice,
        buyMatcherFee = (BigInt(mf) * amount / buy.amount).toLong,
        sellMatcherFee = (BigInt(mf) * amount / sell.amount).toLong,
        fee = mf,
        timestamp = NTP.correctedTime()
      )
      .explicitGet()
      .json()

    tx
  }

  def orders(version: Byte = 2) = {
    val buyer               = acc1
    val seller              = acc0
    val matcher             = acc2
    val time                = NTP.correctedTime()
    val expirationTimestamp = time + Order.MaxLiveTime
    val buyPrice            = 1 * Order.PriceConstant
    val sellPrice           = (0.50 * Order.PriceConstant).toLong
    val mf                  = 700000L
    val buyAmount           = 2
    val sellAmount          = 3
    val assetPair           = AssetPair.createAssetPair(exchAsset, "WAVES").get
    val buy                 = Order.buy(buyer, matcher, assetPair, buyAmount, buyPrice, time, expirationTimestamp, mf, version)
    val sell                = Order.sell(seller, matcher, assetPair, sellAmount, sellPrice, time, expirationTimestamp, mf, version)

    (buy, sell)
  }

}
