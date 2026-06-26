#!/usr/bin/env python3
"""Q5 — Train a tiny MobileNetV2-inspired CNN on chess crops and export TFLite.

Loads dataset from generate_synthetic_data.py output, trains a classifier
with <200K parameters, and exports a quantized TFLite model.

Usage:
  python train_chess_cnn.py
      [--data-dir DATA_DIR] [--output-dir OUTPUT_DIR]
      [--epochs EPOCHS] [--batch-size BATCH_SIZE]
      [--learning-rate LR] [--seed SEED]
"""

import argparse, os, sys, csv
from pathlib import Path

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd
from PIL import Image

import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.model_selection import train_test_split

NUM_CLASSES = 13
CLASS_NAMES = [
    "empty", "wP", "wN", "wB", "wR", "wQ", "wK",
    "bP", "bN", "bB", "bR", "bQ", "bK",
]
IMG_SIZE = 64


# ─── Data loading ────────────────────────────────────────────────────────────
def load_dataset(data_dir):
    """Load images and labels from CSVs. Returns (X_train, y_train, X_val, y_val)."""
    data_dir = Path(data_dir)
    img_dir = data_dir / "images"

    def read_csv(name):
        path = data_dir / name
        if not path.exists():
            return [], []
        df = pd.read_csv(path)
        imgs, labels = [], []
        for _, row in df.iterrows():
            fpath = img_dir / row["filename"]
            if fpath.exists():
                img = Image.open(fpath).convert("RGB").resize((IMG_SIZE, IMG_SIZE))
                imgs.append(np.array(img, dtype=np.float32) / 255.0)
                labels.append(int(row["class_id"]))
        return np.array(imgs), np.array(labels)

    X_train, y_train = read_csv("train.csv")
    X_val, y_val = read_csv("val.csv")

    if len(X_train) == 0 and len(X_val) == 0:
        # Fallback: load all images and split
        print("CSVs not found. Loading all images and doing random split.")
        imgs, labels = [], []
        for f in sorted(img_dir.glob("*.png")):
            cid = int(f.stem.split("_")[0])
            img = Image.open(f).convert("RGB").resize((IMG_SIZE, IMG_SIZE))
            imgs.append(np.array(img, dtype=np.float32) / 255.0)
            labels.append(cid)
        if len(imgs) == 0:
            raise FileNotFoundError(f"No images found in {img_dir}")
        imgs, labels = np.array(imgs), np.array(labels)
        X_train, X_val, y_train, y_val = train_test_split(
            imgs, labels, test_size=0.2, random_state=42, stratify=labels
        )

    y_train = keras.utils.to_categorical(y_train, NUM_CLASSES)
    y_val = keras.utils.to_categorical(y_val, NUM_CLASSES)
    print(f"Loaded {len(X_train)} train, {len(X_val)} val samples")
    return X_train, y_train, X_val, y_val


# ─── Model definition ────────────────────────────────────────────────────────
def _inv_res_block(x, filters, stride, expand_ratio):
    """MobileNetV2 inverted residual block."""
    in_ch = x.shape[-1]
    shortcut = x

    # Expansion
    if expand_ratio > 1:
        x = layers.Conv2D(in_ch * expand_ratio, 1, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU(6.0)(x)

    # Depthwise
    x = layers.DepthwiseConv2D(3, strides=stride, padding="same", use_bias=False)(x)
    x = layers.BatchNormalization()(x)
    x = layers.ReLU(6.0)(x)

    # Projection
    x = layers.Conv2D(filters, 1, padding="same", use_bias=False)(x)
    x = layers.BatchNormalization()(x)

    # Skip connection
    if stride == 1 and in_ch == filters:
        x = layers.Add()([shortcut, x])
    return x


def build_model():
    """Build a tiny MobileNetV2-inspired CNN with <200K parameters."""
    inp = keras.Input(shape=(IMG_SIZE, IMG_SIZE, 3))

    # Stem
    x = layers.Conv2D(16, 3, strides=2, padding="same", use_bias=False)(inp)
    x = layers.BatchNormalization()(x)
    x = layers.ReLU(6.0)(x)

    # Inverted residual blocks — designed to stay under 200K params
    x = _inv_res_block(x, 16, 1, 1)
    x = _inv_res_block(x, 24, 2, 3)
    x = _inv_res_block(x, 24, 1, 3)
    x = _inv_res_block(x, 40, 2, 3)
    x = _inv_res_block(x, 40, 1, 3)
    x = _inv_res_block(x, 64, 2, 3)
    x = _inv_res_block(x, 64, 1, 3)
    x = _inv_res_block(x, 96, 2, 3)
    x = _inv_res_block(x, 96, 1, 3)

    # Head
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Dense(NUM_CLASSES, activation="softmax")(x)

    model = keras.Model(inp, x)
    return model


# ─── Training ────────────────────────────────────────────────────────────────
def plot_history(history, path):
    """Save training history plot to path."""
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4))
    ax1.plot(history.history["accuracy"], label="train")
    ax1.plot(history.history["val_accuracy"], label="val")
    ax1.set_title("Accuracy")
    ax1.set_xlabel("Epoch")
    ax1.legend()
    ax2.plot(history.history["loss"], label="train")
    ax2.plot(history.history["val_loss"], label="val")
    ax2.set_title("Loss")
    ax2.set_xlabel("Epoch")
    ax2.legend()
    plt.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print(f"  Saved training plot to {path}")


def convert_tflite(model, val_images, output_dir):
    """Convert Keras model to quantized int8 TFLite."""
    def rep_dataset():
        for i in range(min(200, len(val_images))):
            yield [val_images[i:i+1]]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = rep_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.float32
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()
    tflite_path = Path(output_dir) / "chess_cnn.tflite"
    tflite_path.write_bytes(tflite_model)

    size_mb = len(tflite_model) / (1024 * 1024)
    print(f"  Exported TFLite model: {tflite_path} ({size_mb:.2f} MB)")

    # Save labels
    labels_path = Path(output_dir) / "labels.txt"
    labels_path.write_text("\n".join(CLASS_NAMES))
    print(f"  Saved labels: {labels_path}")

    return tflite_model, size_mb


def evaluate_tflite(tflite_model, X_val, y_val):
    """Run inference through TFLite model and compute accuracy."""
    interpreter = tf.lite.Interpreter(model_content=tflite_model)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    correct = 0
    for i in range(len(X_val)):
        inp = X_val[i:i+1].astype(np.float32)
        interpreter.set_tensor(input_details[0]["index"], inp)
        interpreter.invoke()
        pred = interpreter.get_tensor(output_details[0]["index"])
        if np.argmax(pred) == np.argmax(y_val[i]):
            correct += 1

    acc = correct / len(X_val)
    return acc


# ─── Main ────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="Train chess CNN & export TFLite")
    parser.add_argument("--data-dir", default="tools/synthetic_data",
                        help="Dataset directory (with images/ and CSVs)")
    parser.add_argument("--output-dir", default="tools/models",
                        help="Output directory for model artifacts")
    parser.add_argument("--epochs", type=int, default=50, help="Training epochs")
    parser.add_argument("--batch-size", type=int, default=64, help="Batch size")
    parser.add_argument("--learning-rate", type=float, default=0.001, help="Learning rate")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    args = parser.parse_args()

    tf.random.set_seed(args.seed)
    np.random.seed(args.seed)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Data
    print("Loading dataset ...")
    X_train, y_train, X_val, y_val = load_dataset(args.data_dir)
    print(f"  Train: {X_train.shape}, Val: {X_val.shape}")

    # Model
    print("Building model ...")
    model = build_model()
    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=args.learning_rate),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    model.summary()

    # Count params
    total_params = model.count_params()
    print(f"Total parameters: {total_params:,}")
    if total_params > 200_000:
        print(f"WARNING: Model exceeds 200K parameters ({total_params:,})")

    # Callbacks
    checkpoint_path = out_dir / "best_checkpoint.weights.h5"
    callbacks = [
        keras.callbacks.ModelCheckpoint(
            str(checkpoint_path), save_best_only=True, save_weights_only=True,
            monitor="val_accuracy", mode="max"
        ),
        keras.callbacks.EarlyStopping(
            patience=10, restore_best_weights=True, monitor="val_accuracy", mode="max"
        ),
        keras.callbacks.ReduceLROnPlateau(
            factor=0.5, patience=5, min_lr=1e-6, monitor="val_accuracy"
        ),
    ]

    # tf.data with on-the-fly augmentation
    train_ds = tf.data.Dataset.from_tensor_slices((X_train, y_train))
    aug = keras.Sequential([
        layers.RandomFlip("horizontal"),
        layers.RandomRotation(0.08),
        layers.RandomBrightness(0.1),
    ])
    train_ds = train_ds.map(
        lambda x, y: (aug(x, training=True), y),
        num_parallel_calls=tf.data.AUTOTUNE,
    )
    train_ds = train_ds.batch(args.batch_size).prefetch(tf.data.AUTOTUNE)

    val_ds = tf.data.Dataset.from_tensor_slices((X_val, y_val))
    val_ds = val_ds.batch(args.batch_size).prefetch(tf.data.AUTOTUNE)

    # Train
    print(f"Training for {args.epochs} epochs ...")
    history = model.fit(
        train_ds,
        epochs=args.epochs,
        validation_data=val_ds,
        callbacks=callbacks,
        verbose=1,
    )

    # Evaluate
    val_loss, val_acc = model.evaluate(X_val, y_val, verbose=0)
    print(f"Validation accuracy: {val_acc:.4f}")

    # Plot history
    plot_history(history, out_dir / "training_history.png")

    # TFLite conversion
    print("Converting to TFLite (int8 quantized) ...")
    tflite_model, size_mb = convert_tflite(model, X_val, out_dir)

    # TFLite accuracy
    tflite_acc = evaluate_tflite(tflite_model, X_val, y_val)
    print(f"TFLite validation accuracy: {tflite_acc:.4f}")

    print(f"\nDone. Model size: {size_mb:.2f} MB, accuracy: {tflite_acc:.4f}")


if __name__ == "__main__":
    main()
