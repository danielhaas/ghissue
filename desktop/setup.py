from setuptools import setup, find_packages

setup(
    name="ghissue",
    version="1.0.0",
    packages=find_packages(),
    install_requires=[
        "requests>=2.28",
        "PyGObject>=3.42",
    ],
    entry_points={
        "console_scripts": [
            "ghissue=ghissue.main:main",
        ],
    },
    python_requires=">=3.10",
    package_data={
        "": ["../resources/*.svg"],
    },
    data_files=[
        ("share/ghissue/resources", ["resources/ghissue-icon.svg"]),
    ],
)
