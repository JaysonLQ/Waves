package com.wavesplatform.it

import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.util._
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import com.wavesplatform.account.PrivateKeyAccount
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.utils.ScorexLogging
import com.wavesplatform.transaction.transfer._
import play.api.libs.json.JsNumber

trait IntegrationSuiteWithThreeAddresses
    extends BeforeAndAfterAll
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with RecoverMethods
    with IntegrationTestsScheme
    with Nodes
    with ScorexLogging {
  this: Suite =>

  def notMiner: Node

  protected def sender: Node = notMiner

  protected lazy val firstAddress: String  = sender.createAddress()
  protected lazy val secondAddress: String = sender.createAddress()
  protected lazy val thirdAddress: String  = sender.createAddress()

  def pkByAddress(address: String) = PrivateKeyAccount.fromSeed(sender.seed(address)).right.get

  abstract protected override def beforeAll(): Unit = {
    super.beforeAll()

    val defaultBalance: Long = 100.waves

    def dumpBalances(node: Node, accounts: Seq[String], label: String): Unit = {
      accounts.foreach(acc => {
        val (balance, eff) = notMiner.accountBalances(acc)

        val formatted = s"$acc: balance = $balance, effective = $eff"
        log.debug(s"$label account balance:\n$formatted")
      })
    }

    def waitForTxsToReachAllNodes(txIds: Seq[String]) = {
      val txNodePairs = for {
        txId <- txIds
        node <- nodes
      } yield (node, txId)

      txNodePairs.foreach({ case (node, tx) => node.waitForTransaction(tx) })
    }

    def makeTransfers(accounts: Seq[String]): Seq[String] = accounts.map { acc =>
      sender.transfer(sender.address, acc, defaultBalance, sender.fee(TransferTransactionV1.typeId)).id
    }

    def correctStartBalancesFuture(): Unit = {
      nodes.waitForHeight(2)
      val accounts = Seq(firstAddress, secondAddress, thirdAddress)

      dumpBalances(sender, accounts, "initial")
      val txs = makeTransfers(accounts)

      val height = nodes.map(_.height).max

      withClue(s"waitForHeight(${height + 2})") {
        nodes.waitForHeight(height + 2)
      }

      withClue("waitForTxsToReachAllNodes") {
        waitForTxsToReachAllNodes(txs)
      }

      dumpBalances(sender, accounts, "after transfer")
      accounts.foreach(notMiner.assertBalances(_, defaultBalance, defaultBalance))
    }

    withClue("beforeAll") {
      correctStartBalancesFuture()
    }
  }

  def setContract(contractText: String, acc: PrivateKeyAccount) = {
    val script = if (Option(contractText).isDefined) {
      val scriptText = contractText.stripMargin
      Some(ScriptCompiler(scriptText).explicitGet()._1)
    } else None
    val setScriptTransaction = SetScriptTransaction
      .selfSigned(SetScriptTransaction.supportedVersions.head, acc, script, 0.014.waves, System.currentTimeMillis())
      .right
      .get
    val setScriptId = sender
      .signedBroadcast(setScriptTransaction.json() + ("type" -> JsNumber(SetScriptTransaction.typeId.toInt)))
      .id
    nodes.waitForHeightAriseAndTxPresent(setScriptId)
  }

}
