package org.elastic4play.services

import javax.inject.{ Inject, Provider, Singleton }

import scala.collection.immutable

import org.elastic4play.models.BaseModelDef

@Singleton
class ModelSrv @Inject() (models: Provider[immutable.Set[BaseModelDef]]) {
  private[ModelSrv] lazy val modelMap = models.get.map(m => m.name -> m).toMap
  def apply(modelName: String) = modelMap.get(modelName)
  lazy val list = models.get.toSeq
}